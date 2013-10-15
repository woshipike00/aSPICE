/**
 * Copyright (C) 2012 Iordan Iordanov
 * 
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.iiordanov.bVNC;

import java.io.IOException;
import java.util.Arrays;

import com.iiordanov.android.drawing.RectList;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

/**
 * @author Michael A. MacDonald
 *
 */
class PartialBitmapData extends AbstractBitmapData {
	int xoffset;
	int yoffset;
	/**
	 * Multiply this times total number of pixels to get estimate of process size with all buffers plus
	 * safety factor
	 */
	static final int CAPACITY_MULTIPLIER = 6;

	private int capacity;
	private int displayWidth;
	private int displayHeight;
	private Rect validRect;

	class Drawable extends AbstractBitmapDrawable {
		private final static String TAG = "Drawable";
		int drawWidth;
		int drawHeight; 
		int xo, yo;
		Paint paint;
		Rect toDraw;
		
		/**
		 * @param data
		 */
		public Drawable(AbstractBitmapData data) {
			super(data);
			paint = new Paint ();
		}

		/* (non-Javadoc)
		 * @see android.graphics.drawable.DrawableContainer#draw(android.graphics.Canvas)
		 */
		@Override
		public void draw(Canvas canvas) {
			toDraw = canvas.getClipBounds();
			
			// To avoid artifacts, we need to enlarge the box by one pixel in all directions.
			toDraw.set(toDraw.left-1, toDraw.top-1, toDraw.right+1, toDraw.bottom+1);
			Log.e("LBM", "toDraw: " + toDraw.toString());
			Log.e("LBM", "validRect: " + validRect.toString());
			toDraw.intersect(validRect);
			drawWidth  = toDraw.width();
			drawHeight = toDraw.height();

			if (toDraw.left < 0)
				xo = 0;
			else if (toDraw.left >= data.bitmapwidth)
				return;
			else
				xo = toDraw.left;

			if (toDraw.top < 0)
				yo = 0;
			else if (toDraw.top >= data.bitmapheight)
				return;
			else
				yo = toDraw.top;

			if (xo + drawWidth  >= data.bitmapwidth)
				drawWidth  = data.bitmapwidth  - xo;
			if (yo + drawHeight >= data.bitmapheight)
				drawHeight = data.bitmapheight - yo;

			try {
				canvas.drawBitmap(data.bitmapPixels, offset(xo, yo), data.bitmapwidth, 
									xo, yo, drawWidth, drawHeight, false, null);

			} catch (Exception e) {
				Log.e (TAG, "Failed to draw bitmap: xo, yo/drawW, drawH: " + xo + ", " + yo + "/"
						+ drawWidth + ", " + drawHeight);
				// In case we couldn't draw for some reason, try putting up text.
				paint.setColor(Color.WHITE);
				canvas.drawText("There was a problem drawing the remote desktop on the screen. " +
						"Please disconnect and reconnect to the VNC server.", xo+50, yo+50, paint);
			}

			if (softCursor != null) {
				canvas.drawBitmap(softCursor, cursorRect.left, cursorRect.top, null);
			}
		}
	}
	
	/**
	 * @param p
	 * @param c
	 */
	public PartialBitmapData(RfbConnectable p, VncCanvas c, int displayWidth, int displayHeight, int capacity) {
		super(p, c);
		this.capacity = capacity;
		this.displayWidth = displayWidth;
		this.displayHeight = displayHeight;
		framebufferwidth=rfb.framebufferWidth();
		framebufferheight=rfb.framebufferHeight();
		bitmapwidth=1024;
		bitmapheight=768;
		android.util.Log.i("PBM", "bitmapsize = ("+bitmapwidth+","+bitmapheight+")");
		bitmapPixels = new int[bitmapwidth * bitmapheight];
		validRect = new Rect (0, 0, bitmapwidth, bitmapheight);
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#copyRect(android.graphics.Rect, android.graphics.Rect, android.graphics.Paint)
	 */
	@Override
	public void copyRect(int sx, int sy, int dx, int dy, int w, int h) {
		int srcOffset, dstOffset;
		int dstH = h;
		int dstW = w;
		
		int startSrcY, endSrcY, dstY, deltaY;
		if (sy > dy) {
			startSrcY = sy;
			endSrcY = sy + dstH;
			dstY = dy;
			deltaY = +1;
		} else {
			startSrcY = sy + dstH - 1;
			endSrcY = sy - 1;
			dstY = dy + dstH - 1;
			deltaY = -1;
		}
		for (int y = startSrcY; y != endSrcY; y += deltaY) {
			srcOffset = offset(sx, y);
			dstOffset = offset(dx, dstY);
			try {
				System.arraycopy(bitmapPixels, srcOffset, bitmapPixels, dstOffset, dstW);
			} catch (Exception e) {
				// There was an index out of bounds exception, but we continue copying what we can. 
				e.printStackTrace();
			}
			dstY += deltaY;
		}
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#createDrawable()
	 */
	@Override
	AbstractBitmapDrawable createDrawable() {
		return new Drawable(this);
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#drawRect(int, int, int, int, android.graphics.Paint)
	 */
	@Override
	void drawRect(int x, int y, int w, int h, Paint paint) {
		int color = paint.getColor();
		int offset = offset(x,y);
		if (w > 10)
		{
			for (int j = 0; j < h; j++, offset += bitmapwidth)
			{
				Arrays.fill(bitmapPixels, offset, offset + w, color);
			}
		}
		else
		{
			for (int j = 0; j < h; j++, offset += bitmapwidth - w)
			{
				for (int k = 0; k < w; k++, offset++)
				{
					bitmapPixels[offset] = color;
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#offset(int, int)
	 */
	@Override
	public int offset(int x, int y) {
		return (y - yoffset) * bitmapwidth + x - xoffset;
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#scrollChanged(int, int)
	 */
	@Override
	void scrollChanged(int newx, int newy) {
		int oxo = xoffset;
		int oyo = xoffset;
		xoffset = newx;
		yoffset = newy;
		int visibleWidth = vncCanvas.getVisibleWidth();
		int visibleHeight = vncCanvas.getVisibleHeight();
		validRect.set (xoffset, yoffset, xoffset + bitmapwidth, yoffset + bitmapheight);
		// If the scroll has taken the visible window outside the current bitmap, then request update.
		if (newx < oxo || newy < oyo || newx + visibleWidth > oxo + bitmapwidth || newy + visibleHeight > oyo + bitmapheight ) {
			Log.i ("PBM", "Writing Full Update Request.");
			vncCanvas.writeFullUpdateRequest(false);
		}

	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#frameBufferSizeChanged(RfbProto)
	 */
	@Override
	public void frameBufferSizeChanged () {
		int newbitmapwidth, newbitmapheight;
		xoffset = 0;
		yoffset = 0;
		framebufferwidth  = rfb.framebufferWidth();
		framebufferheight = rfb.framebufferHeight();
		double scaleMultiplier = Math.sqrt((double)(capacity * 1024 * 1024) /
								(double)(CAPACITY_MULTIPLIER * framebufferwidth * framebufferheight));
		if (scaleMultiplier > 1)
			scaleMultiplier = 1;
		newbitmapwidth=(int)((double)framebufferwidth * scaleMultiplier);
		if (newbitmapwidth < displayWidth)
			newbitmapwidth = displayWidth;
		newbitmapheight=(int)((double)framebufferheight * scaleMultiplier);
		if (newbitmapheight < displayHeight)
			newbitmapheight = displayHeight;
		android.util.Log.i("PBM", "bitmapsize changed = ("+bitmapwidth+","+bitmapheight+")");
		newbitmapwidth=1024;
		newbitmapheight=768;
		if ( newbitmapwidth != bitmapwidth || newbitmapheight != bitmapheight) {
			bitmapPixels = null;
			System.gc();
			bitmapwidth  = newbitmapwidth;
			bitmapheight = newbitmapheight;
			bitmapPixels = new int[bitmapwidth * bitmapheight];
		}
	}
	
	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#syncScroll()
	 */
	@Override
	void syncScroll() {
		// Don't need to do anything here
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#updateBitmap(int, int, int, int)
	 */
	@Override
	public void updateBitmap(int x, int y, int w, int h) {
		// Don't need to do anything here
	}
	
	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#updateBitmap(int, int, int, int)
	 */
	@Override
	public void updateBitmap(Bitmap b, int x, int y, int w, int h) {
		b.getPixels(bitmapPixels, offset(x, y), bitmapwidth, 0, 0, w, h);
	}

	/* (non-Javadoc)
	 * @see com.iiordanov.bVNC.AbstractBitmapData#validDraw(int, int, int, int)
	 */
	@Override
	public boolean validDraw(int x, int y, int w, int h) {
		return x-xoffset>=0 && x-xoffset+w<=bitmapwidth && y-yoffset>=0 && y-yoffset+h<=bitmapheight;
	}
}
