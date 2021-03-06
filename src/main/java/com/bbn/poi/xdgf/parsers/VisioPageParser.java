/*
 * Copyright (c) 2015 Raytheon BBN Technologies Corp
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bbn.poi.xdgf.parsers;


import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.POIXMLException;
import org.apache.poi.xdgf.usermodel.XDGFConnection;
import org.apache.poi.xdgf.usermodel.XDGFPage;
import org.apache.poi.xdgf.usermodel.XDGFPageContents;
import org.apache.poi.xdgf.usermodel.XDGFShape;
import org.apache.poi.xdgf.usermodel.XDGFText;
import org.apache.poi.xdgf.usermodel.shape.ShapeDataAcceptor;
import org.apache.poi.xdgf.usermodel.shape.ShapeVisitor;
import org.apache.poi.xdgf.usermodel.shape.exceptions.StopVisiting;

import rx.Observable;

import com.bbn.poi.xdgf.geom.GeomUtils;
import com.bbn.poi.xdgf.parsers.rx.Rx;
import com.bbn.poi.xdgf.parsers.rx.SpatialTools;
import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;

/**
 * Turns a visio page into a graph
 */
public class VisioPageParser {

	protected Graph graph;
	protected SemanticHelper helper;
	
	protected class SplitData {
		public ShapeData s1;
		public ShapeData s2;
		
		public SplitData(ShapeData s1, ShapeData s2) {
			this.s1 = s1;
			this.s2 = s2;
		}
	}
	
	protected class IntersectionData {
		public ShapeData other;
		public Point2D point;

		public IntersectionData(ShapeData other, Point2D point) {
			this.other = other;
			this.point = point;
		}
	}
	
	protected class GroupData {
		public ShapeData group;
		public ArrayList<ShapeData> children;
		
		@Override
		public String toString() {
			return "[GroupData " + group + "]";
		}
	}
	
	// indices
	protected RTree<ShapeData, Rectangle> rtree = RTree.create();
	protected final Map<Long, ShapeData> shapesMap = new HashMap<>();
	protected final List<ShapeData> shapes = new ArrayList<>();
	protected final Map<String, Edge> edges = new HashMap<>();
	
	// shapes removed from the graph
	protected final List<GroupData> groupShapes = new ArrayList<>();
	
	// secondary sets of groups
	protected final List<GroupData> secondaryGroupShapes = new ArrayList<>();
	
	// convenience
	protected final long pageId;
	protected final String pageName;
	protected final XDGFPageContents pageContents;
	
	// for allocating new shapes -- decrement each time a new shape is created
	protected long shapeIdAllocator = -42;

	public VisioPageParser(XDGFPage page) {
		this(page, new SemanticHelper(), new TinkerGraph());
	}
	
	public VisioPageParser(XDGFPage page, SemanticHelper helper) {
		this(page, helper, new TinkerGraph());
	}
	
	public VisioPageParser(XDGFPage page, SemanticHelper helper, Graph graph) {
		this.graph = graph;
		this.helper = helper;
		
		pageId = page.getID();
		pageName = page.getName();
		pageContents = page.getContent();
	}
	
	public Graph getGraph() {
		return graph;
	}
	
	// processes the page and creates a graph from it
	public void process() {
		
		// TODO: there are a lot of O(N) operations here... 
		
		collectShapes();
		collectConnections();
		
		removeBoringShapes();
		
		// before we perform analysis, sort the shapes
		// - Can't do this earlier, removeBoringShapes depends on the ordering
		Collections.sort(shapes, new ShapeData.OrderByLargestAreaFirst());
		
		associateText();
		joinGroupedShapes();
		addGroupLabels();
		inferConnections();
		
		
		inferGroupConnections();
		
		removeConnectionsAt2Dobjects();
	}
	
	// create vertices from interesting shapes
	protected void collectShapes() {
		
		pageContents.visitShapes(new ShapeVisitor() {
			
			@Override
			public org.apache.poi.xdgf.usermodel.shape.ShapeVisitorAcceptor getAcceptor() {
				return new ShapeDataAcceptor();
			};
			
			@Override
			public void visit(XDGFShape shape, AffineTransform globalTransform, int level) {
				
				ShapeData shapeData = new ShapeData(shape, globalTransform);
				
				if (shapeData.hasText && reassignTextNodeToParent(shape, shapeData)) {
					return;
				}
				
				String id = pageId + ": " + shape.getID();
				Vertex vertex = graph.addVertex(id);
				
				shapeData.vertex = vertex;
				
				// useful properties for later... 
				vertex.setProperty("label", shape.getTextAsString());
				vertex.setProperty("shapeId", shape.getID());
				
				vertex.setProperty("group", "");
				vertex.setProperty("groupId", "");
				vertex.setProperty("inSecondaryGroup", false);
				vertex.setProperty("is1d", shape.isShape1D());
				vertex.setProperty("name", shape.getName());
				vertex.setProperty("pageName", pageName);
				vertex.setProperty("symbolName", shape.getSymbolName());
				vertex.setProperty("type", shape.getShapeType());
				
				// this isn't actually accurate
				//vertex.setProperty("visible", shape.isVisible());
				
				// local coordinates
				vertex.setProperty("x", shapeData.getCenterX());
				vertex.setProperty("y", shapeData.getCenterY());
				
				helper.onCreate(shapeData, shape);

				shapesMap.put(shape.getID(), shapeData);
				shapes.add(shapeData);
			}
		});
	}
	
	protected boolean reassignTextNodeToParent(XDGFShape shape, ShapeData shapeData) {
	
		// keep looking at parents to see if they're a good match
		ShapeData parentMatch = null;
		XDGFShape current = shape;
		ArrayList<ShapeData> duplicates = new ArrayList<>();
		
		double x = shapeData.bounds.getMinX();
		double width = shapeData.bounds.getWidth();
		
		while (current.hasParent()) {
			
			XDGFShape parent = current.getParentShape();
			ShapeData parentData = getShape(parent.getID());
			
			if (parentData != null) {
			
				double parentWidth = parentData.bounds.getWidth();
				double px = parentData.bounds.getMinX();
				
				if (Math.abs(width - parentWidth) > 0.0001 || Math.abs(px - x) > 0.0001)
					break;
				
				// found a potential match
				if (!parentData.hasText) {
					
					// discard duplicate useless shapes
					if (parentMatch != null)
						duplicates.add(parentMatch);
					
					parentMatch = parentData;
				}
			}
			
			current = parent;
		}
		
		// if there's a parent match, reassign the text
		if (parentMatch != null) {
			XDGFText text = shape.getText();
			
			parentMatch.vertex.setProperty("label", shape.getTextAsString());
			parentMatch.vertex.setProperty("textRef", shape.getID());
			parentMatch.vertex.setProperty("textRefWhy", "reassignToParent");
			parentMatch.hasText = true;
			parentMatch.isInteresting = true;
			parentMatch.textCenter = text.getTextCenter();
			
			helper.onReassignToParent(parentMatch, shape);
			
			for (ShapeData dup: duplicates) {
				removeShape(dup);
			}
			
			return true;
		}
		
		return false;
	}
	
	// create a list of the existing ('real', not inferred) connections
	protected void collectConnections() {
		
		if (!helper.useRealConnections())
			return;
		
		for (XDGFConnection conn: pageContents.getConnections()) {
			// if we get the connection point, then it has to be in real coordinates
			
			Double x = null, y = null;
			XDGFShape from = conn.getFromShape();
			XDGFShape to = conn.getToShape();
			
			ShapeData fromShapeData = findShapeOrParent(from.getID());
			
			switch (conn.getFromPart()) {
				case XDGFConnection.visBegin:
					x = fromShapeData.path1Dstart.getX();
					y = fromShapeData.path1Dstart.getY();
					break;
				case XDGFConnection.visEnd:
					x = fromShapeData.path1Dend.getX();
					y = fromShapeData.path1Dend.getY();
					break;
				default:
					break;
			}
			
			createEdge(from, to, "real", x, y);
		}
	}
	
	protected void removeBoringShapes() {
		
		// remove the boring shapes -- we only kept them so far because one of the 
		// reasons a shape could be interesting is if there was a connection to it
		
		// if there isn't, get rid of it so we don't have extra stuff in the output
		
		for (final ShapeData shapeData: shapes) {
			
			if (shapeData.removed)
				continue;
			
			if (!shapeData.isInteresting && shapeData.vertex.getProperty("type").equals("Group")) {
			
				final List<ShapeData> children = new ArrayList<>();
				
				// if not interesting -- but, all of the children are either groups or shapes..
				// .. remove the kids?
				try {
					shapeData.shape.visitShapes(new ShapeVisitor() {
						
						@Override
						public void visit(XDGFShape shape, AffineTransform globalTransform, int level) {
							
							if (shape.getID() == shapeData.shapeId)
								return;
							
							ShapeData child = getShape(shape.getID());
							if (child != null) {
								if (child.hasText || !child.shape.getSymbolName().isEmpty())
									throw new StopVisiting();
								
								children.add(child);
							}
						}
					}, 0);
				} catch (StopVisiting e) {
					children.clear();
				}
				
				// if deemed interesting, remove kids and mark self as interesting
				if (!children.isEmpty()) {
					shapeData.isInteresting = true;
					for (ShapeData child: children) {
						
						// if child has connections, move them over to the new interesting shape
						for (Edge edge: child.vertex.getEdges(Direction.BOTH)) {
							ShapeData other = getShapeFromEdge(edge, Direction.IN);
							if (other == child)
								other = getShapeFromEdge(edge, Direction.OUT);
							
							Double x = edge.getProperty("x");
							Double y = edge.getProperty("y");
							
							createEdge(shapeData, other, "real-moved", x, y);
							edge.remove();
						}
						
						removeShape(child);
					}
				}
			}
			
			// if it's interesting -- or if it has a connection, then keep it
			if (shapeData.isInteresting || shapeData.vertex.getEdges(Direction.BOTH).iterator().hasNext()) {

				// add to the tree
				// - RTree only deals with bounding rectangles, so we need
				//   to get the bounding rectangle in local coordinates, put
				//   into a polygon (to allow accounting for rotation), and
				//   then get the minimum bounding rectangle.
				
				// TODO: is it worth setting up a custom geometry?
				// -> problem with a custom geometry is that calculating the
				//    distance between objects would be annoying
				rtree = rtree.add(shapeData, shapeData.rtreeBounds);
				
			} else {
				removeShape(shapeData);
			}
		}
		
		cleanShapes();
	}
	
	protected void joinGroupedShapes() {
		
		// find groups of shapes that are visually together
		// - Must overlap
		// - Must be same type
		// - Must not contain each other
		// - Only one must have text
		
		// we can't actually make a group here, as that makes things like
		// bounding rectangles annoying. Instead, just link them together
		// with an edge 
		
		// insert naive implementation here
		for (final ShapeData shapeData: shapes) {
			
			if (shapeData.is1d())
				continue;
			
			final String symbolName = shapeData.vertex.getProperty("symbolName");
			if (symbolName.equals(""))
				continue;
			
			Observable<Entry<ShapeData, Rectangle>> entries = rtree.search(shapeData.rtreeBounds);
			
			entries.forEach(new Rx.RTreeAction() {

				@Override
				public void call(Entry<ShapeData, Rectangle> entry) {
					ShapeData other = entry.value();
					
					if (other == shapeData || other.is1d())
						return;
					
					// if the intersection is equal to the area of the smallest, then
					// we can assume one of them contains the other
					// .. don't want those to be joined
					
					if (!other.vertex.getProperty("symbolName").equals(symbolName) || 
						ShapeData.eitherEncloses(shapeData, other)) {
						return;
					}
					
					// but if it doesn't contain, then link them together
					createEdge(shapeData, other, "linked", null, null);
				}
			});
		}
	}
	
	protected void addGroupLabels() {
		
		// this step finds user defined shapes that contain other shapes
		// visually, but aren't organized as groups
		
		// for those shapes, we pretend they're being used as groups, and set
		// the 'group' key to something
		
		// additionally, if a shape denotes a group, it is excluded from being
		// connected to, and its vertex is removed from the tree/shapedata
		
		for (final ShapeData shapeData: shapes) {
			
			if (shapeData.is1d() || !shapeData.hasText)
				continue;
			
			final boolean inGroup = !shapeData.vertex.getProperty("groupId").equals("");
			
			final ShapeData topmostParent = findTopmostParentWithGeom(shapeData);
			
			final ArrayList<ShapeData> containedShapes = new ArrayList<>();
			final ArrayList<ShapeData> secondaryShapes = new ArrayList<>();
			
			Observable<Entry<ShapeData, Rectangle>> entries = rtree.search(shapeData.rtreeBounds);
			
			entries.forEach(new Rx.RTreeAction() {

				@Override
				public void call(Entry<ShapeData, Rectangle> e) {
					ShapeData other = e.value();
					
					// include 1d shapes? no
					if (other == shapeData || other.is1d())
						return;
					
					// if it visually contains it
					if (shapeData.encloses(other)) {
						
						// ok, what to do here.
						// -- problem: two hierarchies present here
						
						// AND if they're not in the same hierarchy.. unless one is the topmost parent
						if (!inGroup && (topmostParent == null || topmostParent != findTopmostParentWithGeom(other))) { 
							containedShapes.add(other);
						} else {
							secondaryShapes.add(other);
						}
					}
				}
			});
			
			if (!containedShapes.isEmpty()) {
				
				String groupName = shapeData.vertex.getProperty("label");
				Object groupId = shapeData.vertex.getId();
				
				for (ShapeData other: containedShapes) {
					other.vertex.setProperty("group", groupName);
					other.vertex.setProperty("groupId", groupId);
				}
				
				// store group information for later usage
				GroupData group = new GroupData();
				group.children = containedShapes;
				group.group = shapeData;
				groupShapes.add(group);
				
				removeShape(shapeData);
				
				helper.onGroup(shapeData, containedShapes);
				
			} else if (!secondaryShapes.isEmpty()) {
				
				for (ShapeData other: secondaryShapes) {
					other.vertex.setProperty("inSecondaryGroup", true);
					// TODO: technically, could be part of multiple secondary groups...
					other.vertex.setProperty("secondaryGroup", shapeData.vertex.getProperty("label"));
				}
				
				// this is a secondary group, it doesn't get removed from the graph yet
				GroupData group = new GroupData();
				group.children = secondaryShapes;
				group.group = shapeData;
				secondaryGroupShapes.add(group);
				
				helper.onSecondaryGroup(shapeData, secondaryShapes);
			}
		}
		
		cleanShapes();
	}
	
	
	
	protected void inferConnections() {
		
		// first, infer connections between 1d objects and 2d objects,
		// split the 1d objects where they join the 2d objects, and make
		// connections. Do this first because this case is easier to deal
		// with than the combined case
		
		LinkedList<ShapeData> newShapes = new LinkedList<>();
		
		for (ShapeData shapeData: shapes) {
			if (shapeData.is1d()) {
				infer2dConnections(shapeData, newShapes);
			}
		}
		
		// add the new shapes, remove the old shapes
		cleanShapes();
		
		for (ShapeData shapeData: newShapes) {
			shapes.add(shapeData);
			shapesMap.put(shapeData.shapeId, shapeData);
		}
		
		//
		// Do a dumb algorithm to find 1d lines that overlap but aren't
		// connected, and connect them
		//
		
		for (ShapeData shapeData: shapes) {
			if (shapeData.is1d()) {
				infer1dConnections(shapeData);
			}
		}
		
		// next, try to collect all 1d networks, and replace the lines
		// with new lines more fully representing the connectedness of
		// the graph
		
		/**
		 * Alternate idea for dealing with 1D lines:
		 * 
		 * - Gather all the neighborhoods of connected lines
		 * - For each neighborhood:
		 * 		- Break into path components
		 * 		- Make appropriate connections to 2d objects
		 *      - Iterate path components, find intersections
		 *      - Create a mini-graph from this
		 *      - Create an elided graph by traversal? 
		 * 
		 */
	}
	
	protected void infer2dConnections(final ShapeData shapeData, LinkedList<ShapeData> newShapes) {
		
		final Set<ShapeData> connections = new HashSet<>();
		
		// create a list of real things that I'm attached to
		final Set<Vertex> attached = Sets.newHashSet(shapeData.vertex.getVertices(Direction.BOTH, "real"));
		
		// identify any shapes that it overlaps with
		// add that shape to the list of connections
		Observable<Entry<ShapeData, Rectangle>> entries = rtree.search(shapeData.rtreeBounds);
		
		entries.subscribe(new Rx.RTreeSubscriber() {

			@Override
			public void onNext(Entry<ShapeData, Rectangle> e) {
				
				ShapeData other = e.value();
				if (other == shapeData)
					return;
				
				// discard 1d shapes, textboxes, shapes that are already attached, or shapes
				// that don't intersect
				if (other.is1d() || other.isTextbox)
					return;
				
				// Don't create new connections to things it's already attached to
				if (attached.contains(other.vertex))
					return;
				
				if (!GeomUtils.pathIntersects(shapeData.path1D, other.path2D))
					return;
				
				// if we get here, then we've inferred a new connection
				
				// if either of this line's endpoints are inside the 2d shape,
				// then just create a connection and be done with it
				if (GeomUtils.isInsideOrOnBoundary(other.path2D, shapeData.path1Dstart)) {
					Point2D p = shapeData.path1Dstart;
					createEdge(shapeData, other, "inferred-2d", p.getX(), p.getY());
				} else if (GeomUtils.isInsideOrOnBoundary(other.path2D, shapeData.path1Dend)) {
					Point2D p = shapeData.path1Dend;
					createEdge(shapeData, other, "inferred-2d", p.getX(), p.getY());
				} else {
					connections.add(other);
				}
			}
		});
		
		if (connections.isEmpty())
			return;
		
		// add existing connections to the list, removing those edges
		// -> would like to avoid this... not sure how
		
		// ok, the existing connection data has a record of the connection
		// point, so we can use that if we stored it... but we don't
		
		List<ShapeData> connectedToStart = new LinkedList<>();
		List<ShapeData> connectedToEnd = new LinkedList<>();
		
		
		for (Edge edge: shapeData.vertex.getEdges(Direction.BOTH)) {
			ShapeData in = getShapeFromEdge(edge, Direction.IN);
			ShapeData out = getShapeFromEdge(edge, Direction.OUT);
			ShapeData other;
			Path2D otherPath;
			
			if (in != shapeData) {
				other = in;
			} else if (out != shapeData) {
				other = out;
			} else {
				throw new POIXMLException("Internal error processing existing connections");
			}
			
			otherPath = other.getPath();
			
			if (GeomUtils.isInsideOrOnBoundary(otherPath, shapeData.path1Dstart))
				connectedToStart.add(other);
			else if (GeomUtils.isInsideOrOnBoundary(otherPath, shapeData.path1Dend))
				connectedToEnd.add(other);
			else
				connections.add(in);
				
			graph.removeEdge(edge);
		}
		
		// at this point, all items in connections must be in the middle somewhere,
		// and will cause a split of some kind
		
		// list of new shapedata
		Path2D.Double currentPath = new Path2D.Double();
		
		ShapeData lastShape = null;
		
		Point2D textCenter = shapeData.textCenter;
		ShapeData textShape = null;
		double textDistance = Double.MAX_VALUE;
		
		PathIterator pit = shapeData.path1D.getPathIterator(null, 0.01);
		double[] coords = new double[6];
        double lastX = 0, lastY = 0;
        final Point2D firstPt = shapeData.path1Dstart;
        
        // coordinate of the last connection point
        Double currentX = null;
        Double currentY = null;
        
        
		
		while (!pit.isDone()) {
			
			int type = pit.currentSegment(coords);
            switch(type) {
            	case PathIterator.SEG_MOVETO:
            		if (currentX == null) {
            			currentX = coords[0];
            			currentY = coords[1];
            		}
            		
            		currentPath.moveTo(coords[0], coords[1]);
            		break;
            	case PathIterator.SEG_LINETO:
            		
            		Line2D.Double line = new Line2D.Double(lastX, lastY,
							   coords[0], coords[1]);
            		
            		List<IntersectionData> intersections = new ArrayList<>();
            		
            		for (ShapeData connectedShape: connections) {
            			
            			List<Point2D> points = new LinkedList<>();
            			
            			if (GeomUtils.findIntersections(connectedShape.getPath(), line, points, 0.01)) {
            				// found a split point, add it to the list
            				for (Point2D point: points) {
            					intersections.add(new IntersectionData(connectedShape, point));
            				}
            			}
            		}
            		
            		if (intersections.isEmpty()) {
            			currentPath.lineTo(coords[0], coords[1]);
            		} else {
            			// sort the points from start to finish
            			Collections.sort(intersections, new Comparator<IntersectionData>() {
							@Override
							public int compare(IntersectionData o1, IntersectionData o2) {
								
								double d = firstPt.distance(o1.point) - firstPt.distance(o2.point);
								if (d < 0)
									return -1;
								else if (d > 0)
									return 1;
								else
									return 0;  
							}
            			});
            			
            			for (IntersectionData intersection: intersections) {
	            			
            				// elide connections to self
            				if (lastShape == intersection.other)
            					continue;
            				
            				// ok. create a path and stuff.
            				double iX = intersection.point.getX();
            				double iY = intersection.point.getY();
            				
            				currentPath.lineTo(iX, iY);
	            			
	            			// create a new shapeData
	            			ShapeData thisShape = clone1dShape(currentPath, shapeData);
	            			newShapes.add(thisShape);
	            			
	            			// see if this is closest to the text
	            			if (shapeData.hasText) {
		            			double thisTextDistance = GeomUtils.pathDistance(thisShape.path1D, textCenter);
		            			if (thisTextDistance < textDistance)
		            				textShape = thisShape;
	            			}
	            			
	            			// create edges joining them
	            			if (lastShape == null) {
	            				for (ShapeData shape: connectedToStart)
	            					createEdge(thisShape, shape, "inferred2d-split-start", currentX, currentY);
	            				
	            			} else {
	            				createEdge(lastShape, thisShape, "inferred2d-split-middle", currentX, currentY);
	            			}
	            			
	            			createEdge(thisShape, intersection.other, "inferred2d-split-middle", currentX, currentY);
	            			
	            			lastShape = intersection.other;
            			
	            			currentPath = new Path2D.Double();
	            			currentPath.moveTo(iX, iY);
	            			
	            			currentX = iX;
	            			currentY = iY;
            			}
            			
            			currentPath.lineTo(coords[0], coords[1]);
            		}
            		
            		break;
            	default:
            		throw new POIXMLException();
            }
            
            lastX = coords[0];
            lastY = coords[1];
			
			pit.next();
		}
		
		
		// finish off the path here
		ShapeData thisShape = clone1dShape(currentPath, shapeData);
		newShapes.add(thisShape);
		
		// see if this is closest to the text
		if (shapeData.hasText) {
			double thisTextDistance = GeomUtils.pathDistance(thisShape.path1D, textCenter);
			if (thisTextDistance < textDistance)
				textShape = thisShape;
			
			// ok, now that it's done, reassociate the text
			textShape.hasText = true;
			textShape.textCenter = shapeData.textCenter;
			textShape.vertex.setProperty("label", shapeData.vertex.getProperty("label"));
			textShape.vertex.setProperty("textRef", shapeData.shapeId);
			textShape.vertex.setProperty("textRefWhy", "reassign2dClosest");
			
			helper.onAssignText(shapeData, textShape);
		}
		
		createEdge(lastShape, thisShape, "inferred2d-split-next-end", currentX, currentY);
		
		for (ShapeData shape: connectedToEnd)
			createEdge(thisShape, shape, "inferred2d-split-end", lastX, lastY);
		
		removeShape(shapeData);
	}
	
	protected void infer1dConnections(final ShapeData shapeData) {
		
		// create a list of things that I'm attached to
		final Set<Vertex> attached = Sets.newHashSet(shapeData.vertex.getVertices(Direction.BOTH));
		
		// identify any shapes that it overlaps with
		// add that shape to the list of connections
		Observable<Entry<ShapeData, Rectangle>> entries = rtree.search(shapeData.rtreeBounds);
		
		entries.subscribe(new Rx.RTreeSubscriber() {
			
			@Override
			public void onNext(Entry<ShapeData, Rectangle> e) {
				
				ShapeData other = e.value();
				
				if (other == shapeData || other.removed || !other.is1d() || attached.contains(other.vertex))
					return;
				
				// don't infer connections between lines of different colors
				// or different line patterns
				if (!shapeData.lineColor.equals(other.lineColor) || shapeData.linePattern != other.linePattern) {
					return;
				}
				
				// compute if they intersect
				List<Point2D> intersections = new ArrayList<>();
				
				if (!GeomUtils.findIntersections(shapeData.path1D, other.path1D, intersections, null)) {
					return;
				}
				
				// TODO
				// if they are both dynamic connectors, don't create connections
				// unless their intersection is at the end of a line?
				// alternatively, try to check if the intersection happens at an
				// 'arcto' point. if so, discard, as that's a 'clear' visual indicator
				// that it should not be connected
				
				// ok, we've gotten here, create a connection between the two lines
				// -> connection point is first point.. not sure what to do with other points
				Point2D intersection = intersections.get(0);
				createEdge(shapeData, other, "inferred-1d", intersection.getX(), intersection.getY());
			}
		});
	}
	
	protected void associateText() {
		
		// ordered by largest first
		for (ShapeData shapeData: shapes) {
			
			if (!shapeData.isTextbox || shapeData.removed)
				continue;
			
			associateTextboxWithShape(shapeData);
		}
		
		cleanShapes();
	}
	
	
	/**
	 * this takes a shape that is a 'textbox'
	 */
	protected void associateTextboxWithShape(final ShapeData textBox) {
		
		// limit the search to some reasonable number/distance (TODO: what is reasonable)
		
		Observable<Entry<ShapeData, Rectangle>> entries = SpatialTools.nearest(rtree, textBox.rtreeBounds, helper.textInferenceDistance(textBox), rtree.size());
		
		final List<ShapeData> maybe = new ArrayList<>();
		
		entries.subscribe(new Rx.RTreeSubscriber() {
			
			@Override
			public void onNext(Entry<ShapeData, Rectangle> e) {
				
				ShapeData other = e.value();
				
				if (other == textBox || other.hasText || other.removed || !helper.onTextInference(textBox, other))
					return;
				
				// if it encloses it, only associate if there's nothing else closer
				if (other.encloses(textBox)) {
					if (maybe.isEmpty())
						maybe.add(other);
					
					return;
				}
				
				doAssociateTextboxWithShape(textBox, other);
				maybe.clear();
				
				// TODO: probably want to be more intelligent, and assign the text to
				//       things that are nearer in a particular direction, taking 
				//       advantage of how a human might naturally align the text..
				
				// done with this
				unsubscribe();
			}
		});
		
		// if we didn't find any alternatives, associate the first one that enclosed
		if (!maybe.isEmpty())
			doAssociateTextboxWithShape(textBox, maybe.get(0));
	}
	
	protected void doAssociateTextboxWithShape(ShapeData textBox, ShapeData other) {
		other.vertex.setProperty("label", textBox.vertex.getProperty("label"));
		other.vertex.setProperty("textRef", textBox.shapeId);
		other.vertex.setProperty("textRefWhy", "associateWithShape");
		other.hasText = true;
		other.textCenter = textBox.textCenter;
		
		// move any edges from the textbox to us
		for (Edge edge: textBox.vertex.getEdges(Direction.BOTH)) {
			
			ShapeData in = getShapeFromEdge(edge, Direction.IN);
			ShapeData out = getShapeFromEdge(edge, Direction.OUT);
			
			if (in != other && out != other) {
				
				Double x = edge.getProperty("x");
				Double y = edge.getProperty("y");
				
				if (in == textBox)
					createEdge(out, other, "reparent", x, y);
				else if (out == textBox)
					createEdge(in, other, "reparent", x, y);
				else
					throw new POIXMLException("Internal error");
			}
			
			graph.removeEdge(edge);
		}
		
		helper.onAssignText(textBox, other);
		
		// remove the textbox from the tree so others can't use it
		removeShape(textBox);
	}
	
	protected void inferGroupConnections() {

		//
		// if a group consists entirely (mostly?) of disconnected shapes, then
		// we should see if there are connections to the group. If there is,
		// then we should connect all of the internal shapes to the connections
		//
		
		//
		// Process formal groups first
		//
		
		// for each group
		for (final GroupData groupData: groupShapes) {
			
			if (!groupIsMostlyDisconnected(groupData, true))
				continue;
			
			List<ShapeData> connections = new ArrayList<>();
				
			inferDisconnectedGroupConnections(groupData, connections, false);
			connectDisconnectedGroup(groupData, connections);
		}
		
		// 
		// Process things that look like possible groups
		//
		
		for (GroupData groupData: secondaryGroupShapes) {
			
			if (!groupIsMostlyDisconnected(groupData, false))
				continue;
			
			final List<ShapeData> connections = new ArrayList<>();
			final Path2D groupPath = groupData.group.getPath();
			
			// secondary groups are still in the graph, so they probably have vertices
			// associated with them
			for (Edge e: groupData.group.vertex.getEdges(Direction.BOTH)) {
				
				Vertex v = e.getVertex(Direction.IN);
				if (v == groupData.group.vertex)
					v = e.getVertex(Direction.OUT);
				
				ShapeData other = getShape((long)v.getProperty("shapeId"));
				
				if (!other.is1d())
					continue;
				
				boolean has2dConnection = false;
				
				// check to see if it is connected to a 2d shape that overlaps this shape
				for (Vertex vv: v.getVertices(Direction.BOTH)) {
					if (vv == groupData.group.vertex)
						continue;
					
					ShapeData oo = getShape((long)vv.getProperty("shapeId"));
					if (oo.is1d())
						continue;
					
					if (GeomUtils.pathIntersects(groupPath, oo.getPath()))
						has2dConnection = true;
				}
				
				if (!e.getLabel().startsWith("real") &&
					!GeomUtils.pathIntersects(groupPath, other.path1Dstart) &&
					!GeomUtils.pathIntersects(groupPath, other.path1Dend) &&
					!has2dConnection) {
					continue;
				}
				
				connections.add(other);
			}
			
			// if nothing found, see if there are 2d shapes to connect to
			if (connections.size() == 0)
				inferDisconnectedGroupConnections(groupData, connections, true);
			
			connectDisconnectedGroup(groupData, connections);
		}
		
		cleanShapes();
	}
	
	protected boolean groupIsMostlyDisconnected(GroupData groupData, boolean ignoreSecondary) {
		
		int disconnectedShapes = 0;
		int totalShapes = 0;
		
		// calculate the number of disconnected shapes that have text
		for (ShapeData child: groupData.children) {
			
			if (child.removed || !child.hasText || child.is1d())
				continue;
			
			if (ignoreSecondary && child.vertex.getProperty("inSecondaryGroup").equals(true))
				continue;
			
			if (!child.vertex.getEdges(Direction.BOTH).iterator().hasNext())
				disconnectedShapes += 1;
			
			totalShapes += 1;
		}
	
		// if there are more than two disconnected 2d shapes, search for a
		// connection to the group itself
		return disconnectedShapes != 0 && (disconnectedShapes >= totalShapes/2 || disconnectedShapes == totalShapes);
	}
	
	protected void inferDisconnectedGroupConnections(final GroupData groupData, final List<ShapeData> connections, final boolean ignore1d) {
		// identify any shapes that the group overlaps with
		// add that shape to the list of connections
		Observable<Entry<ShapeData, Rectangle>> entries = rtree.search(groupData.group.rtreeBounds);
		
		final Path2D groupPath = groupData.group.getPath();
		
		entries.subscribe(new Rx.RTreeSubscriber() {

			@Override
			public void onNext(Entry<ShapeData, Rectangle> e) {
				
				ShapeData other = e.value();
				if (other == groupData.group)
					return;
				
				if (other.is1d()) {
					
					// TODO: what we probably want is a function that checks all segments of the path 
					//       -- but only matches on the end segments matching
					
					if (ignore1d)
						return;
					
					// check to see if one of the endpoints of the 1d shape intersects
					// with the group
					if (!GeomUtils.pathIntersects(groupPath, other.path1Dstart) &&
					    !GeomUtils.pathIntersects(groupPath, other.path1Dend)) {
						return;
					}
					
				} else {
					
					if (!other.vertex.getVertices(Direction.BOTH).iterator().hasNext() ||  
						!GeomUtils.pathIntersects(groupPath, other.path2D)) {
						return;
					}
				}
				
				connections.add(other);
			}
		});
	}
	
	protected void connectDisconnectedGroup(GroupData groupData, List<ShapeData> connections) {
		
		if (connections.isEmpty())
			return;
		
		if (!groupData.group.removed) {
			removeShape(groupData.group);
		}
		
		// if there's a connection, then connect that connection to all of the children
		for (ShapeData child: groupData.children) {
			if (child.removed || !child.hasText)
				continue;
			
			// create connection
			for (ShapeData connection: connections) {
				createEdge(child, connection, "inferred-disconnected-group", null, null);
			}
		}
	}
	
	protected void removeConnectionsAt2Dobjects() {
		
		// this is a cleanup operation -- if there are connection points
		// for 1d objects that exist at a 2d shape that they are connected
		// to, then remove the connection
		
		for (ShapeData shape: shapes) {
			
			if (!shape.is1d())
				continue;
			
			Set<Long> collected2dObjects = null;
			
			// get the connection point from the edge properties
			for (Edge edge: shape.vertex.getEdges(Direction.BOTH)) {
				Double x = edge.getProperty("x");
				if (x == null)
					continue;
				
				Double y = edge.getProperty("y");
				
				// ok, the other end must be a 1d object. Find the object.
				// Find all 2d objects that I'm connected to, and see if the
				// other object is connected to any of them.
				
				if (collected2dObjects == null)
					collected2dObjects = collect2dObjects(shape.vertex);
				
				// if both connected to the same object, see if the x/y overlaps
				Vertex other = edge.getVertex(Direction.IN);
				if (other == shape.vertex)
					other = edge.getVertex(Direction.OUT);
				
				Set<Long> other2dObjects = collect2dObjects(other);
				
				for (Long o: other2dObjects) {
					if (collected2dObjects.contains(o)) {
						ShapeData sd = getShape(o);
						if (sd.bounds.intersects(x - 0.00001, y - 0.00001, 0.00002, 0.00002)) {
							// remove edge if it overlaps
							edge.remove();
						}
					}
				}
			}
		}
	}
	
	Set<Long> collect2dObjects(Vertex v) {
		
		Set<Long> collected = new HashSet<>();
		
		for (Vertex other: v.getVertices(Direction.BOTH)) {
			if (other.getProperty("is1d"))
				continue;
			
			collected.add((Long)other.getProperty("shapeId"));
		}
		
		return collected;
	}
	
	
	protected void createEdge(XDGFShape shape1, XDGFShape shape2, String edgeType, Double x, Double y) {
		
		ShapeData sd1 = findShapeOrParent(shape1.getID());
		ShapeData sd2 = findShapeOrParent(shape2.getID());
		
		if (sd1 == null) 
			throw new POIXMLException("Cannot find from node " + shape1.getID());
		
		if (sd2 == null) 
			throw new POIXMLException("Cannot find to node " + shape2.getID());
		
		// TODO: how to deal with from/to being null? Might happen.
		createEdge(sd1, sd2, edgeType, x, y);
	}
	
	
	// edgeType is a string describing where the edge came from
	// x/y is the coordinate where the connection occurs
	protected void createEdge(ShapeData sd1, ShapeData sd2, String edgeType, Double x, Double y) {
	
		// note: visio doesn't always support direction, and neither do we. So, to
		//       save time, and make sure we don't accidentally create duplicate 
		//       edges, we sort by id
		
		ShapeData from = sd1;
		ShapeData to = sd2;
		
		if (sd1.shapeId > sd2.shapeId) {
			from = sd2;
			to = sd1;
		}
		
		String eId = getConnId(from, to);
		
		Edge edge = graph.getEdge(eId);
		if (edge == null) {
			edge = graph.addEdge(eId, from.vertex, to.vertex, edgeType);
			
			if (x != null && y != null) {
				edge.setProperty("x", x);
				edge.setProperty("y", y);
			}
		}
	}
	
	protected ShapeData getShape(long id) {
		ShapeData sd = shapesMap.get(id);
		if (sd != null && !sd.removed)
			return sd;
		
		return null;
	}
	
	protected ShapeData findShapeOrParent(long id) {
		
		ShapeData sd = getShape(id);
		if (sd != null)
			return sd;
		
		// find a parent that is in the graph already
		XDGFShape shape = pageContents.getShapeById(id);
		
		while (sd == null) {
			shape = shape.getParentShape();
			if (shape == null)
				break;
			
			sd = getShape(shape.getID());
		}
		
		return sd;
	}
	
	protected ShapeData findTopmostParentWithGeom(ShapeData shapeData) {
		
		ShapeData shapeWithGeom = (shapeData.hasGeometry ? shapeData: null);
		
		XDGFShape shape = pageContents.getShapeById(shapeData.shapeId);
		
		while (true) {
			shape = shape.getParentShape();
			if (shape == null)
				break;
			
			shapeData = getShape(shape.getID());
			if (shapeData != null && shapeData.hasGeometry)
				shapeWithGeom = shapeData;
		}
		
		return shapeWithGeom;
	}
	
	protected String getConnId(ShapeData from, ShapeData to) {
		
		String fromId = "" + from.shapeId;
		String toId = "" + to.shapeId;
		
		return pageId + ": " + fromId + " -> " + toId;
	}
	
	protected ShapeData getShapeFromEdge(Edge edge, Direction direction) {
		return getShape((Long)edge.getVertex(direction).getProperty("shapeId"));
	}
	
	protected void cleanShapes() {
		
		Iterator<ShapeData> i = shapesMap.values().iterator();
		
		while (i.hasNext()) {
			if (i.next().removed)
				i.remove();
		}
		
		i = shapes.iterator();
		
		while (i.hasNext()) {
			if (i.next().removed)
				i.remove();
		}
	}
	
	protected void removeShape(ShapeData shapeData) {
		shapeData.removed = true;
		graph.removeVertex(shapeData.vertex);
		rtree = rtree.delete(new Entry<ShapeData, Rectangle>(shapeData, shapeData.rtreeBounds));
	}
	
	protected ShapeData clone1dShape(Path2D.Double newPath, ShapeData oldShape) {
		
		long shapeId = shapeIdAllocator--;
		
		Vertex oldVertex = oldShape.vertex;
		Vertex vertex = graph.addVertex(pageId + ": " + shapeId);
		
		// copy properties
		for (String p: oldVertex.getPropertyKeys())
			vertex.setProperty(p, oldVertex.getProperty(p));
		
		vertex.setProperty("label", "");
		vertex.setProperty("shapeId", shapeId);
		vertex.setProperty("shapeRef", oldShape.shapeId);
		
		ShapeData newShape = new ShapeData(shapeId, vertex, oldShape, newPath);
		rtree = rtree.add(newShape, newShape.rtreeBounds);
		
		vertex.setProperty("x", newShape.getCenterX());
		vertex.setProperty("y", newShape.getCenterY());
		
		helper.onClone1d(oldShape, newShape);
		
		return newShape;
	}
	
}
