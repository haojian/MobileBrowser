package com.android.erowser;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import android.graphics.Canvas;

public class ErowserWebView extends WebView {
	public ErowserWebView(Context context) {
		super(context);
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		EventMonitor.getInstance().sendWebViewDispatchKey(event);
		return super.dispatchKeyEvent(event);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		EventMonitor.getInstance().sendOnDraw(this);
	}

	@Override
	public void computeScroll() {
		super.computeScroll();
		if (mScrollX != mOldScrollX || mScrollY != mOldScrollY) {
			EventMonitor.getInstance().sendScroll(mScrollX, mScrollY);
			mOldScrollX = mScrollX;
			mOldScrollY = mScrollY;
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		EventMonitor.getInstance().sendWebViewSizeChanged(this, w, h, ow, oh);
		super.onSizeChanged(w, h, ow, oh);
	}

	// This can not call super function. Because they are in different packages.
	public void updateCachedTextfield(String updatedText) {
		EventMonitor.getInstance().sendUpdateTextfield(updatedText);
		// super.updateCachedTextfield(updatedText);
		// nativeUpdateCachedTextfield(updatedText, mTextGeneration++);
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		EventMonitor.getInstance().sendWebViewOnTouch(this, ev);
		return super.onTouchEvent(ev);
	}

	@Override
	public boolean onTrackballEvent(MotionEvent ev) {
		EventMonitor.getInstance().sendWebViewOnTrackball(ev);
		return super.onTrackballEvent(ev);
	}

	@Override
	public boolean performLongClick() {
		EventMonitor.getInstance().sendLongClick();
		return super.performLongClick();
	}

	private static int mOldScrollX = -1;
	private static int mOldScrollY = -1;
}
