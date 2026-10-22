package com.cheatprogram.render;

import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Line icons drawn with Java2D on a 24x24 grid (stroke style similar to Lucide). */
public enum Icon {
	SEARCH, CHEVRON_RIGHT, CHEVRON_LEFT, CHEVRON_DOWN, CHEVRON_UP, MORE, GEAR, LAYOUT, CLOSE, BACK,
	STAR, STAR_FILLED, SWORD, NAVIGATION, EYE, WRENCH, GLOBE, BRIEFCASE, TERMINAL, SHIELD,
	USER, PLUS, TRASH, CHECK, KEYBOARD, SAVE, DOWNLOAD, FOLDER, BELL, TARGET, SPARKLE, POWER,
	REFRESH, GRIP, INFO, LANGUAGE, LINK, CUBE, ACTIVITY, SERVER, MUSIC, NETEASE;

	void paint(Graphics2D g) {
		switch (this) {
			case SEARCH -> {
				g.draw(circle(11, 11, 6.8f));
				g.draw(line(16, 16, 20.5f, 20.5f));
			}
			case CHEVRON_RIGHT -> g.draw(poly(9, 5.5f, 15.5f, 12, 9, 18.5f));
			case CHEVRON_LEFT -> g.draw(poly(15, 5.5f, 8.5f, 12, 15, 18.5f));
			case CHEVRON_DOWN -> g.draw(poly(5.5f, 9, 12, 15.5f, 18.5f, 9));
			case CHEVRON_UP -> g.draw(poly(5.5f, 15, 12, 8.5f, 18.5f, 15));
			case MORE -> {
				g.fill(circle(12, 5, 1.9f));
				g.fill(circle(12, 12, 1.9f));
				g.fill(circle(12, 19, 1.9f));
			}
			case GEAR -> {
				Area gear = new Area(circle(12, 12, 6.9f));
				for (int i = 0; i < 8; i++) {
					Shape tooth = new RoundRectangle2D.Float(12 - 2.1f, 1.8f, 4.2f, 6f, 1.6f, 1.6f);
					gear.add(new Area(AffineTransform.getRotateInstance(Math.toRadians(i * 45), 12, 12).createTransformedShape(tooth)));
				}
				g.draw(gear);
				g.draw(circle(12, 12, 3.1f));
			}
			case LAYOUT -> {
				g.draw(new RoundRectangle2D.Float(3.5f, 3.5f, 7, 9, 2.4f, 2.4f));
				g.draw(new RoundRectangle2D.Float(13.5f, 3.5f, 7, 5, 2.4f, 2.4f));
				g.draw(new RoundRectangle2D.Float(13.5f, 11.5f, 7, 9, 2.4f, 2.4f));
				g.draw(new RoundRectangle2D.Float(3.5f, 15.5f, 7, 5, 2.4f, 2.4f));
			}
			case CLOSE -> {
				g.draw(line(6.5f, 6.5f, 17.5f, 17.5f));
				g.draw(line(17.5f, 6.5f, 6.5f, 17.5f));
			}
			case BACK -> {
				g.draw(circle(12, 12, 9.3f));
				g.draw(poly(13.6f, 8, 9.6f, 12, 13.6f, 16));
			}
			case STAR -> g.draw(star());
			case STAR_FILLED -> {
				g.fill(star());
				g.draw(star());
			}
			case SWORD -> {
				g.draw(poly(14.5f, 17.5f, 3, 6, 3, 3, 6, 3, 17.5f, 14.5f));
				g.draw(line(13, 19, 19, 13));
				g.draw(line(16, 16, 20, 20));
				g.draw(line(19, 21, 21, 19));
			}
			case NAVIGATION -> {
				Path2D p = poly(3.5f, 11, 21, 3, 13, 20.5f, 11, 13);
				p.closePath();
				g.draw(p);
			}
			case EYE -> {
				Path2D p = new Path2D.Float();
				p.moveTo(2, 12);
				p.curveTo(4.5, 6.6, 8, 4.8, 12, 4.8);
				p.curveTo(16, 4.8, 19.5, 6.6, 22, 12);
				p.curveTo(19.5, 17.4, 16, 19.2, 12, 19.2);
				p.curveTo(8, 19.2, 4.5, 17.4, 2, 12);
				p.closePath();
				g.draw(p);
				g.draw(circle(12, 12, 3));
			}
			case WRENCH -> {
				Area head = new Area(circle(15.5f, 8.5f, 5.2f));
				head.subtract(new Area(circle(19.2f, 4.8f, 2.7f)));
				Shape handle = new RoundRectangle2D.Float(-1.75f, 0, 3.5f, 12.5f, 3.5f, 3.5f);
				AffineTransform t = AffineTransform.getTranslateInstance(12.2f, 11.8f);
				t.rotate(Math.toRadians(45));
				head.add(new Area(t.createTransformedShape(handle)));
				g.draw(head);
			}
			case GLOBE -> {
				g.draw(circle(12, 12, 9.3f));
				g.draw(new Ellipse2D.Float(12 - 4.1f, 12 - 9.3f, 8.2f, 18.6f));
				g.draw(line(2.7f, 12, 21.3f, 12));
			}
			case BRIEFCASE -> {
				g.draw(new RoundRectangle2D.Float(2.5f, 7, 19, 13.5f, 4.5f, 4.5f));
				Path2D p = new Path2D.Float();
				p.moveTo(8.5, 7);
				p.lineTo(8.5, 5);
				p.quadTo(8.5, 3.5, 10, 3.5);
				p.lineTo(14, 3.5);
				p.quadTo(15.5, 3.5, 15.5, 5);
				p.lineTo(15.5, 7);
				g.draw(p);
				g.draw(line(2.5f, 13, 21.5f, 13));
			}
			case TERMINAL -> {
				g.draw(poly(4.5f, 17, 10, 11.5f, 4.5f, 6));
				g.draw(line(12, 18, 19.5f, 18));
			}
			case SHIELD -> {
				Path2D p = new Path2D.Float();
				p.moveTo(12, 21.5);
				p.curveTo(12, 21.5, 19.5, 18, 19.5, 12);
				p.lineTo(19.5, 5.5);
				p.lineTo(12, 2.5);
				p.lineTo(4.5, 5.5);
				p.lineTo(4.5, 12);
				p.curveTo(4.5, 18, 12, 21.5, 12, 21.5);
				p.closePath();
				g.draw(p);
				g.draw(poly(9, 12, 11.2f, 14.2f, 15.2f, 10));
			}
			case USER -> {
				g.draw(circle(12, 8, 4));
				Path2D p = new Path2D.Float();
				p.moveTo(4.5, 20.5);
				p.curveTo(4.5, 16.5, 7.8, 14, 12, 14);
				p.curveTo(16.2, 14, 19.5, 16.5, 19.5, 20.5);
				g.draw(p);
			}
			case PLUS -> {
				g.draw(line(12, 5, 12, 19));
				g.draw(line(5, 12, 19, 12));
			}
			case TRASH -> {
				g.draw(line(3.5f, 6.5f, 20.5f, 6.5f));
				Path2D body = new Path2D.Float();
				body.moveTo(5.5, 6.5);
				body.lineTo(6.5, 19);
				body.quadTo(6.6, 20.5, 8, 20.5);
				body.lineTo(16, 20.5);
				body.quadTo(17.4, 20.5, 17.5, 19);
				body.lineTo(18.5, 6.5);
				g.draw(body);
				Path2D lid = new Path2D.Float();
				lid.moveTo(9, 6.5);
				lid.lineTo(9, 4.5);
				lid.quadTo(9, 3.5, 10, 3.5);
				lid.lineTo(14, 3.5);
				lid.quadTo(15, 3.5, 15, 4.5);
				lid.lineTo(15, 6.5);
				g.draw(lid);
			}
			case CHECK -> g.draw(poly(5, 12.5f, 10, 17, 19, 7.5f));
			case KEYBOARD -> {
				g.draw(new RoundRectangle2D.Float(2.5f, 6, 19, 12, 4, 4));
				g.fill(circle(6.8f, 10, 1.1f));
				g.fill(circle(10.3f, 10, 1.1f));
				g.fill(circle(13.8f, 10, 1.1f));
				g.fill(circle(17.3f, 10, 1.1f));
				g.draw(line(7.5f, 14.2f, 16.5f, 14.2f));
			}
			case SAVE -> {
				g.draw(new RoundRectangle2D.Float(3.5f, 3.5f, 17, 17, 4, 4));
				g.draw(poly(8, 3.5f, 8, 8.5f, 15, 8.5f));
				g.draw(new RoundRectangle2D.Float(7, 13.5f, 10, 7, 1.5f, 1.5f));
			}
			case DOWNLOAD -> {
				g.draw(line(12, 3.5f, 12, 15));
				g.draw(poly(7, 10.5f, 12, 15.5f, 17, 10.5f));
				Path2D p = new Path2D.Float();
				p.moveTo(3.5, 15.5);
				p.lineTo(3.5, 18.5);
				p.quadTo(3.5, 20.5, 5.5, 20.5);
				p.lineTo(18.5, 20.5);
				p.quadTo(20.5, 20.5, 20.5, 18.5);
				p.lineTo(20.5, 15.5);
				g.draw(p);
			}
			case FOLDER -> {
				Path2D p = new Path2D.Float();
				p.moveTo(3, 7);
				p.quadTo(3, 5, 5, 5);
				p.lineTo(9.5, 5);
				p.lineTo(11.5, 7.5);
				p.lineTo(19, 7.5);
				p.quadTo(21, 7.5, 21, 9.5);
				p.lineTo(21, 17.5);
				p.quadTo(21, 19.5, 19, 19.5);
				p.lineTo(5, 19.5);
				p.quadTo(3, 19.5, 3, 17.5);
				p.closePath();
				g.draw(p);
			}
			case BELL -> {
				Path2D p = new Path2D.Float();
				p.moveTo(6, 16);
				p.lineTo(6, 10.5);
				p.curveTo(6, 7, 8.7, 4.5, 12, 4.5);
				p.curveTo(15.3, 4.5, 18, 7, 18, 10.5);
				p.lineTo(18, 16);
				p.lineTo(19.5, 17.5);
				p.lineTo(4.5, 17.5);
				p.closePath();
				g.draw(p);
				Path2D c = new Path2D.Float();
				c.moveTo(10.2, 20.2);
				c.quadTo(12, 21.8, 13.8, 20.2);
				g.draw(c);
			}
			case TARGET -> {
				g.draw(circle(12, 12, 9.3f));
				g.draw(circle(12, 12, 5.2f));
				g.fill(circle(12, 12, 1.4f));
			}
			case SPARKLE -> {
				Path2D p = new Path2D.Float();
				p.moveTo(12, 3);
				p.curveTo(12.8, 9.2, 14.8, 11.2, 21, 12);
				p.curveTo(14.8, 12.8, 12.8, 14.8, 12, 21);
				p.curveTo(11.2, 14.8, 9.2, 12.8, 3, 12);
				p.curveTo(9.2, 11.2, 11.2, 9.2, 12, 3);
				p.closePath();
				g.draw(p);
			}
			case POWER -> {
				g.draw(new Arc2D.Float(4.5f, 5, 15, 15, 60, -300, Arc2D.OPEN));
				g.draw(line(12, 3, 12, 11));
			}
			case REFRESH -> {
				g.draw(new Arc2D.Float(4, 4, 16, 16, 90, -280, Arc2D.OPEN));
				g.draw(poly(9.5f, 1.8f, 12.2f, 4, 9.8f, 6.6f));
			}
			case GRIP -> {
				for (int row = 0; row < 3; row++) {
					g.fill(circle(9, 6 + row * 6, 1.6f));
					g.fill(circle(15, 6 + row * 6, 1.6f));
				}
			}
			case INFO -> {
				g.draw(circle(12, 12, 9.3f));
				g.draw(line(12, 11, 12, 16.5f));
				g.fill(circle(12, 7.8f, 1.25f));
			}
			case LANGUAGE -> {
				g.draw(circle(12, 12, 9.3f));
				g.draw(new Ellipse2D.Float(12 - 4.1f, 12 - 9.3f, 8.2f, 18.6f));
				g.draw(line(3.5f, 8.5f, 20.5f, 8.5f));
				g.draw(line(3.5f, 15.5f, 20.5f, 15.5f));
			}
			case LINK -> {
				g.draw(new RoundRectangle2D.Float(2.5f, 8, 10, 8, 8, 8));
				g.draw(new RoundRectangle2D.Float(11.5f, 8, 10, 8, 8, 8));
				g.draw(line(8.5f, 12, 15.5f, 12));
			}
			case SERVER -> {
				g.draw(new RoundRectangle2D.Float(3, 3, 18, 8, 3, 3));
				g.draw(new RoundRectangle2D.Float(3, 13, 18, 8, 3, 3));
				g.fill(circle(7, 7, 1));
				g.fill(circle(7, 17, 1));
				g.draw(line(12, 7, 17, 7));
				g.draw(line(12, 17, 17, 17));
			}
			case CUBE -> {
				Path2D p = poly(12, 2.5f, 20.5f, 7, 20.5f, 17, 12, 21.5f, 3.5f, 17, 3.5f, 7);
				p.closePath();
				g.draw(p);
				g.draw(poly(3.5f, 7, 12, 11.7f, 20.5f, 7));
				g.draw(line(12, 11.7f, 12, 21.5f));
			}
			case ACTIVITY -> g.draw(poly(2.5f, 12, 6.5f, 12, 9.5f, 4.5f, 14.5f, 19.5f, 17.5f, 12, 21.5f, 12));
			case MUSIC -> {
				g.draw(poly(9, 17, 9, 5, 20, 3, 20, 15));
				g.fill(new Ellipse2D.Float(3, 15, 6, 5));
				g.fill(new Ellipse2D.Float(14, 13, 6, 5));
				g.draw(line(9, 9, 20, 7));
			}
			case NETEASE -> {
				// Open outer ring and the curled note of the music logo, with no coloured tile.
				Stroke original = g.getStroke();
				if (original instanceof BasicStroke stroke)
					g.setStroke(new BasicStroke(stroke.getLineWidth() * 0.86f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Arc2D.Float(3.4f, 4.2f, 17.2f, 16.8f, 110, 300, Arc2D.OPEN));
				Path2D note = new Path2D.Float();
				note.moveTo(18.0, 4.4);
				note.curveTo(15.8, 2.0, 12.4, 2.5, 12.3, 5.4);
				note.curveTo(12.2, 7.3, 15.7, 10.5, 15.4, 12.9);
				note.curveTo(15.1, 15.6, 11.0, 16.2, 9.5, 13.9);
				note.curveTo(8.0, 11.6, 9.3, 8.8, 11.5, 8.6);
				g.draw(note);
				g.setStroke(original);
			}
		}
	}

	private static Shape circle(float cx, float cy, float r) {
		return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
	}

	private static Shape line(float x1, float y1, float x2, float y2) {
		return new Line2D.Float(x1, y1, x2, y2);
	}

	private static Path2D poly(float... xy) {
		Path2D p = new Path2D.Float();
		p.moveTo(xy[0], xy[1]);
		for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
		return p;
	}

	private static Path2D star() {
		Path2D p = new Path2D.Float();
		for (int i = 0; i < 10; i++) {
			double r = i % 2 == 0 ? 9.6 : 4.1;
			double a = Math.toRadians(-90 + i * 36);
			double x = 12 + Math.cos(a) * r;
			double y = 12.8 + Math.sin(a) * r;
			if (i == 0) p.moveTo(x, y);
			else p.lineTo(x, y);
		}
		p.closePath();
		return p;
	}
}
