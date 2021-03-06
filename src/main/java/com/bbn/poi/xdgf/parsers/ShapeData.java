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


import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Comparator;

import org.apache.poi.POIXMLException;
import org.apache.poi.xdgf.usermodel.XDGFShape;

import com.bbn.poi.xdgf.geom.GeomUtils;
import com.bbn.poi.xdgf.parsers.rx.SpatialTools;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.tinkerpop.blueprints.Vertex;

public class ShapeData {

	// orders by largest first
	public static class OrderByLargestAreaFirst implements Comparator<ShapeData> {
		@Override
		public int compare(ShapeData o1, ShapeData o2) {
			return Float.compare(o2.area, o1.area);
		}
	}
	
	public XDGFShape shape = null; // don't use this
	
	public Vertex vertex;
	
	// in global coordinates
	public Rectangle rtreeBounds;	// don't use this except for the rtree, as this is in different precision!
	public float area;
	
	// in global coordinates
	public Path2D path1D = null;
	public Point2D path1Dstart = null;
	public Point2D path1Dend = null;
	
	public Path2D path2D = null;
	public boolean hasGeometry;
	
	public Rectangle2D bounds;
	
	public Point2D textCenter = null;
	
	// don't store the actual shape, just useful attributes needed later
	// -> allows us to join/split shapes at will
	public long shapeId;
	public boolean hasText;
	public boolean isTextbox;
	
	public Color lineColor;
	public Integer linePattern;
	
	public boolean removed = false;
	public boolean isInteresting = false;
	
	
	public ShapeData(XDGFShape shape, AffineTransform globalTransform) {
		
		Path2D shapeBounds;
		Path2D path = shape.getPath();
		
		// some 1d shapes don't have a path associated with them, 
		// if they have subshapes... 
		if (shape.isShape1D() && path != null) {
			path1D = path;
			path1D.transform(globalTransform);
			path1D = GeomUtils.roundPath(path1D);
			hasGeometry = true;
			
			calculate1dEndpoints();
			shapeBounds = path1D; // use path as bounds
		} else {
			// calculate bounding boxes + other geometry information we'll need later
			shapeBounds = shape.getBoundsAsPath();
			
			shapeBounds.transform(globalTransform);
			shapeBounds = GeomUtils.roundPath(shapeBounds);
			
			path2D = shapeBounds;
			hasGeometry = (path != null);
		}
		
		this.bounds = shapeBounds.getBounds2D();
		
		this.shape = shape;
		this.shapeId = shape.getID();
		this.rtreeBounds = SpatialTools.convertRect(this.bounds);
		this.area = this.rtreeBounds.area();
		
		this.isInteresting = isInteresting(shape);
		
		this.lineColor = shape.getLineColor();
		this.linePattern = shape.getLinePattern();
		
		hasText = shape.hasText() && !shape.getTextAsString().isEmpty();
		isTextbox = hasText && !shape.hasMaster() && !shape.hasMasterShape();
		
		if (hasText)
			textCenter = globalTransform.transform(shape.getText().getTextCenter(), null);
	}
	
	// clone 1d shapes
	public ShapeData(long shapeId, Vertex vertex, ShapeData other, Path2D.Double new1dPath) {
		
		this.shapeId = shapeId;
		this.vertex = vertex;
		
		lineColor = other.lineColor;
		linePattern = other.linePattern;
		isInteresting = other.isInteresting;
		
		path1D = new1dPath;
		calculate1dEndpoints();
		
		bounds = new1dPath.getBounds2D();
		rtreeBounds = SpatialTools.convertRect(bounds);
		area = this.rtreeBounds.area();
		
		hasText = false;
		isTextbox = false;
	}
	
	public boolean is1d() {
		return path1D != null;
	}
	
	public Path2D getPath() {
		return path1D != null ? path1D : path2D;
	}
	
	protected void calculate1dEndpoints() {
		// can't use beginX et al here, as it's in parent coordinates
		double[] coords = new double[6];
		if (path1D.getPathIterator(null).currentSegment(coords) != PathIterator.SEG_MOVETO) {
			throw new POIXMLException("Invalid 1d path");
		}
		
		path1Dstart = new Point2D.Double(coords[0], coords[1]);
		path1Dend = path1D.getCurrentPoint();
	}
	
	public float getCenterX() {
		return rtreeBounds.x1() + (rtreeBounds.x2() - rtreeBounds.x1())/2.0F; 
	}
	
	public float getCenterY() {
		return rtreeBounds.y1() + (rtreeBounds.y2() - rtreeBounds.y1())/2.0F; 
	}
	
	public boolean encloses(ShapeData other) {
		return rtreeBounds.intersectionArea(other.rtreeBounds) >= other.area;
	}
	
	public String toString() {
		return "[ShapeData " + shapeId + "]";
	}
	
	public static boolean eitherEncloses(ShapeData s1, ShapeData s2) {
		return s1.rtreeBounds.intersectionArea(s2.rtreeBounds) >= Math.min(s1.area, s2.area);
	}
	
	public static boolean isInteresting(XDGFShape shape) {
		return !shape.getSymbolName().isEmpty() || shape.isShape1D() || shape.hasMaster() || shape.hasText();
	}
	
}
