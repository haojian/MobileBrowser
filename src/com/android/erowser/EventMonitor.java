package com.android.erowser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.erowser.TabControl.Tab;

public class EventMonitor extends Activity implements View.OnCreateContextMenuListener {
	private static final String TAG = "EventMonitor";
	private String buffertimesurfix;// bufferred time+surfix
	// Single instance of the EventMonitor for use in the Erowser app.
	private static EventMonitor sSingleton;
	public TitleBarOnTouchListener mTitleBarOnTouchListener;
	private boolean shown = false;
	private boolean mIsHttps = false;
	private String uid;
	private static int old_orientation = -1;
	private static float old_scale = -1.0f;
	private static boolean old_AcceptsText = false;
	private static boolean old_show_softkey = false;
	//private static final String EVENT_URL = "http://192.168.233.133/cgi-bin/saveEvent.cgi?";
	private static final String EVENT_URL = "http://ir-ub.mathcs.emory.edu/saveAndroid.cgi?";
	//private static final String EVENT_URL = "http://192.168.1.100/cgi-bin/event_recorder.pl?";
	//private static final String EVENT_URL = "http://erowser.heliohost.org/event_recorder.pl?";
	private static final int MIN_HTTP_REQUEST = 1024;
	private EventsProducer mEventsProducer;
	private EventsConsumer mEventsConsumer;
	private WindowManager mWindowManager;
	private InputMethodManager mInputMethodManager;
	private final Semaphore events_in_queue = new Semaphore(0, true);// how many events are in the queue.
	private LinkedList<String> mQueue;
	private static final int EXT_REPRESENTATION_MSG_ID = 67891;
	private final Handler mEPrivateHandler = new EPrivateHandler();
	private String mRenderTreeStr = "";
	private TitleBar mTitleBar;
	private WebView mCurrentWebView = null;
	static int oldProgress = 0;
	private ArrayList<RenderNode> mRenderDB;
	private boolean allowGetRenderTree = true;
	private boolean stopParser = false;
	private String lastViewedContentPageUrl = "error";
	private String urlStorageNameOnSDcard = "erowserHistory.txt";

	class EPrivateHandler extends Handler {
		@Override
		// receive the render tree string and then parse it.
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case EXT_REPRESENTATION_MSG_ID:
				stopParser = false;
				String newStr = (String) msg.obj;
				if ((newStr.length() > 0) && (newStr.length() != mRenderTreeStr.length())) {
					mRenderTreeStr = newStr;
					mRenderDB = createRenderDB(mRenderTreeStr);
				}
				allowGetRenderTree = true;
				break;
			}
		}
	}

	// From a Render Tree String, we create a database for every text string.
	private ArrayList<RenderNode> createRenderDB(String renderTreeStr) {
		ArrayList<RenderNode> newRenderDB = new ArrayList<RenderNode>();
		int L = renderTreeStr.length();
		if ((renderTreeStr != null) && (L > 0)) {
			RenderNode RootNode = new RenderNode();
			RootNode.width = RootNode.height = 65536;
			RootNode.aType = "root";
			int start = 0;
			int rp_start = 0, rp_end = 0;
			String current = renderTreeStr;
			while ((start < 128 * 1024) && (start >= 0) && (start < L) && (!stopParser)) {
				RenderNode parentNode = RootNode;
				int level = 0;
				while ((current.charAt(rp_start + level * 2) == ' ') && (parentNode != null)) {
					parentNode = parentNode.children.get(parentNode.children.size() - 1);
					level++;
				}
				rp_start += level * 2;
				if (parentNode != null) {
					RenderNode childNode = new RenderNode();
					if (!parentNode.aType.equals("RenderText"))
						parentNode.children.clear();// only keep one child and discard all others.
					parentNode.children.add(childNode);
					childNode.aType = current.substring(rp_start, current.indexOf(' ', rp_start));
					if (parentNode.aType.equals("RenderText") || parentNode.aType.equals("RenderInline"))
						childNode.container = parentNode.container;
					else
						childNode.container = parentNode;
					if (childNode.aType.equals("text")) {
						rp_start = current.indexOf('(', rp_start) + 1;
						rp_end = current.indexOf(',', rp_start);
						childNode.relative_x = Integer.parseInt(current.substring(rp_start, rp_end));
						childNode.global_x = childNode.relative_x + childNode.container.global_x;
						rp_start = rp_end + 1;
						rp_end = current.indexOf(')', rp_start);
						childNode.relative_y = Integer.parseInt(current.substring(rp_start, rp_end));
						childNode.global_y = childNode.relative_y + childNode.container.global_y;
						rp_start = rp_end + 8;
						rp_end = current.indexOf(':', rp_start);
						childNode.width = Integer.parseInt(current.substring(rp_start, rp_end));
						childNode.height = 17;
						rp_start = rp_end + 3;
						rp_end = current.indexOf("\"\n", rp_start);
						childNode.text = current.substring(rp_start, rp_end);
						rp_end++;
					} else if (!(childNode.aType.equals("caret:"))) {
						if (current.startsWith("RenderBlock (relative positioned)", rp_start) || current.startsWith("RenderBlock (positioned)", rp_start)
								|| current.startsWith("RenderInline ", rp_start) || current.startsWith("RenderText ", rp_start)) {
							childNode.relative_x = 0;
							childNode.relative_y = 0;
							childNode.width = parentNode.width;
							childNode.height = parentNode.height;
							if (childNode.aType.equals("RenderText")) {
								newRenderDB.add(childNode);
							}
							rp_end = current.indexOf('\n', rp_start);
						} else {
							rp_start = current.indexOf(" at (", rp_start) + 5;
							rp_end = current.indexOf(',', rp_start);
							childNode.relative_x = Integer.parseInt(current.substring(rp_start, rp_end));
							rp_start = rp_end + 1;
							rp_end = current.indexOf(')', rp_start);
							childNode.relative_y = Integer.parseInt(current.substring(rp_start, rp_end));
							rp_start = rp_end + 7;
							rp_end = current.indexOf('x', rp_start);
							childNode.width = Integer.parseInt(current.substring(rp_start, rp_end));
							rp_start = rp_end + 1;
							rp_end = rp_start + 1;
							char c = current.charAt(rp_end);
							while (c >= '0' && c <= '9') {
								rp_end++;
								c = current.charAt(rp_end);
							}
							childNode.height = Integer.parseInt(current.substring(rp_start, rp_end));
							rp_start = rp_end;
							rp_end = current.indexOf('\n', rp_start);
						}
						childNode.global_x = childNode.relative_x + childNode.container.global_x;
						childNode.global_y = childNode.relative_y + childNode.container.global_y;
					} else {
						rp_end = current.indexOf('\n', rp_start);
					}
				} else {
					rp_end = current.indexOf('\n', rp_start);
				}
				start = rp_end + 1;
				rp_start = start;
			}
		}
		return newRenderDB;
	}

	/* package */static EventMonitor getInstance() {
		if (sSingleton == null) {
			sSingleton = new EventMonitor();
		}
		return sSingleton;
	}

	class RenderNode {
		int relative_x, relative_y;
		int global_x, global_y;
		int width, height;
		RenderNode container;
		ArrayList<RenderNode> children;
		String aType;
		String text;

		RenderNode() {
			relative_x = relative_y = 0;
			global_x = global_y = 0;
			width = height = 0;
			container = null;
			children = new ArrayList<RenderNode>();
			aType = "unknown";
			text = "";
		}
	}

	// given a view coordination, return the string at that point.
	// return "" if can not find any string at that position.
	public String getTextFromView(WebView webview, int vx, int vy) {
		String retText = "";
		// convert (vx, vy) from view coordination to content coordination.
		int x = Math.round((float) (vx + webview.getScrollX()) / old_scale);
		int y = Math.round((float) ((vy + webview.getScrollY() - mTitleBar.getHeight())) / old_scale);
		Log.w(TAG, "vx=" + String.valueOf(vx) + ",vy=" + String.valueOf(vy) + ",x=" + String.valueOf(x) + ",y=" + String.valueOf(y));
		retText = getTextFromContent(mRenderDB, x, y);
		return retText;
	}

	// given a content coordination, return the string at that point.
	// return "" if can not find any string at that position.
	private String getTextFromContent(ArrayList<RenderNode> mRenderDB2, int cx, int cy) {
		String ShortStr = "", LongStr = "", AllStr = "";
		int n = mRenderDB2.size();
		RenderNode RenderText = null;
		for (int i = 0; i < n; i++) {
			RenderText = mRenderDB2.get(i);
			if ((cx >= RenderText.global_x) && (cy >= RenderText.global_y) && (cx < RenderText.global_x + RenderText.width)
					&& (cy < RenderText.global_y + RenderText.height)) {
				// we found long string
				for (int j = 0; j < RenderText.children.size(); j++) {
					RenderNode text = RenderText.children.get(j);
					LongStr += text.text + "\n";
					if ((cx >= text.global_x) && (cy >= text.global_y) && (cx < text.global_x + text.width) && (cy < text.global_y + text.height)) {
						// yes, we found short string!
						ShortStr = text.text;
					}
				}
			}
		}
		if ((ShortStr.length() > 0) || (LongStr.length() > 0))
			AllStr = "ShortStr=" + Encode(ShortStr) + "&LongStr=" + Encode(LongStr);
		return AllStr;
	}

	// Start to run the monitor.
	public void StartRun() {
		buffertimesurfix = initPrefix();
		new Thread(mEventsProducer).start();
		new Thread(mEventsConsumer).start();
	}

	// return a string that every event request needs.
	public String versionTimeMark() {
		return "&v=2.1&time=" + String.valueOf(new Date().getTime()) + "&";
	}

	// set up user id so we can know which user does what.
	public void setupUID(String uid_input){
		if(uid_input == "" || uid_input == null)
			uid = "unknown";
		else
			uid = uid_input;
	}
	
	public String getUID(){
		return uid;
	}

	// set up user id so we can know which user does what.
	public String getLocalIpAddress() {
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						return inetAddress.getHostAddress().toString();
					}
				}
			}
		} catch (SocketException ex) {
			Log.e(TAG, ex.toString());
		}
		return null;
	}

	public EventMonitor() {
		mTitleBarOnTouchListener = new TitleBarOnTouchListener();
		mQueue = new LinkedList<String>();
		mEventsProducer = new EventsProducer();
		mEventsConsumer = new EventsConsumer();
	}

	public void setWindowManager(WindowManager pWindowManager) {
		mWindowManager = pWindowManager;
	}

	public void setInputMethodManager(InputMethodManager pInputMethodManager) {
		mInputMethodManager = pInputMethodManager;
	}

	// check Erowser status, create events and put them into queue.
	public class EventsProducer implements Runnable {
		private void checkstates() {
			try {
				/* First, get the Display from the WindowManager */
				Display display = mWindowManager.getDefaultDisplay();
				/* Now we can retrieve all display-related infos */
				int new_orientation = display.getOrientation();
				if (new_orientation != old_orientation) {
					sendOrientation(old_orientation, new_orientation);
					old_orientation = new_orientation;
					sendRenderTreeRequest(mCurrentWebView);// Render will change.
				}
				if (mInputMethodManager.isAcceptingText() != old_AcceptsText) {
					sendInputAcceptsText(!old_AcceptsText);
					old_AcceptsText = !old_AcceptsText;
				}
				if (old_orientation == 1) {// landscape mode
					if (mInputMethodManager.isFullscreenMode() != old_show_softkey) {
						sendShowHideSoftKey(!old_show_softkey);
						old_show_softkey = !old_show_softkey;
					}
				}
			} catch (Exception e) {
			}
		}

		public void run() {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			while (true) {
				try {
					Thread.sleep(500);// to avoid spinning
					if (shown)
						checkstates();
				} catch (InterruptedException e) {
				}
			}
		}
	}

	// handle events in queue and send them to the server through "GET" http requests.
	public class EventsConsumer implements Runnable {
		public void run() {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			while (true) {
				String urlStr = getFirstMessage();
				getUrlData(urlStr, null);
			}
		}
	}

	private String initPrefix() {
		return EVENT_URL + "uid=" + uid;
	}

	public void queueMessage(String tsurfix, boolean force) {
		synchronized (buffertimesurfix) {
			buffertimesurfix += tsurfix;
			if ((buffertimesurfix.length() > MIN_HTTP_REQUEST) || force) {
				mQueue.add(buffertimesurfix);
				buffertimesurfix = initPrefix();
				events_in_queue.release();
			}
		}
	}

	// get first message from the head of the queue.
	public String getFirstMessage() {
		String urlStr = null;
		try {
			events_in_queue.acquire();// will be blocked here if no message in it
			urlStr = mQueue.getFirst();
			mQueue.removeFirst();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return urlStr;
	}

	public String MotionAction(MotionEvent event) {
		switch (event.getAction()) {
		case MotionEvent.ACTION_DOWN:
			return "DOWN";
		case MotionEvent.ACTION_MOVE:
			return "MOVE";
		case MotionEvent.ACTION_UP:
			return "UP";
		case MotionEvent.ACTION_CANCEL:
			return "CANCEL";
		}
		return "UNKNOWN";
	}

	public String KeyAction(KeyEvent event) {
		switch (event.getAction()) {
		case KeyEvent.ACTION_DOWN:
			return "DOWN";
		case KeyEvent.ACTION_MULTIPLE:
			return "MULTIPLE";
		case KeyEvent.ACTION_UP:
			return "UP";
		}
		return "UNKNOWN";
	}

	public class TitleBarOnTouchListener implements OnTouchListener {
		public boolean onTouch(View v, MotionEvent ev) {
			int size = ev.getPointerCount();
			String s = "ev=TitleBarTouch&Action=" + MotionAction(ev) + "&TouchPointNum=" + size;
			for (int i = 0; i < size; i++) {
				s += "&vx" + String.valueOf(i) + "=" + ev.getX(i) + "&vy" + String.valueOf(i) + "=" + ev.getY(i) + "&pressure" + String.valueOf(i) + "=" + ev.getPressure(i) + "&touchsize" + String.valueOf(i) + "=" + ev.getSize(i);
			}
			sendString(s);
			return false;
		}
	}

	// add a header to this string and then send it to the buffer.
	void sendString(String surfix) {
		sendString(surfix, false);
	}

	// add a header to this string and then send it to the buffer.
	void sendString(String surfix, boolean force) {
		String tsurfix = versionTimeMark() + surfix + "&[E]=";// "&[E]=" is used to mark the end of events.
		Log.w(TAG, tsurfix);
		if (!mIsHttps || force) {
			queueMessage(tsurfix, force);
		}
	}

	// simple encoding
	String Encode(String before) {
		String after = null;
		try {
			after = URLEncoder.encode(before, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return after;
	}

	// users press "Go" to do search
	public void sendDoSearch(String url) {
		sendString("ev=Search&SearchStr=" + Encode(url));
	}

	// Program is about to run in foreground
	public void sendResume() {
		sendString("ev=Resume");
	}

	// Program is about to run in background or pause.
	public void sendPause() {
		sendString("ev=Pause");
	}

	// Send user's estimation
	public void sendSelfReportEstimation(int seconds){
		sendString("ev=SelfEstimation&EstimationTimeSeconds=" + seconds);
	}
	
	
	// Send user's frustration reporter
	public void sendFrustrationReport(float int_lvl){
		sendString("ev=FrustrationReport&FrustrationLvl=" + int_lvl);
	}
	
	// Send user's relevance reporter. scale from 0-5
	public void sendRelevanceReport(String url, float i){
		sendString("ev=RelevanceReport&UrlStr="+ Encode(url) + "&RelevanceRate=" + i);
	}
	
	// Program is about to be destroyed
	public void sendDestroy() {
		sendString("ev=DestroyActivity");
	}

	public void sendPageStarted(WebView view, String url) {
		if (!url.startsWith("/")) {
			// it is not relative web address
			mIsHttps = url.startsWith("https://");
		}
		sendString("ev=WebClientPageStart&StartUrl=" + Encode(url));
		mRenderDB = new ArrayList<RenderNode>();
		mRenderTreeStr = "";// clear the render tree string.
	}

	public void sendSetHomePage(String url) {
		sendString("ev=SetHomePage&HomeUrl=" + Encode(url));
	}
	
	public void saveSERPHtmlOnSD(int taskId, int _SERPIndex, String htmlContent){
		String prefix = "SERPHTML_";
		String filename = prefix + uid + "_" + String.valueOf(taskId) + "_" + String.valueOf(_SERPIndex) + ".txt";
		
		SDCardOperator.writeToSDcardFile(filename, "", htmlContent);
	}

	public void sendPageFinished(WebView webView, String url, String taskUId, int _SERPIndex) {
		float new_scale;
		sendString("ev=WebClientPageFinish&FinishUrl=" + Encode(url)+ "&taskuid=" + taskUId + "&serpindex="+ _SERPIndex);
		SDCardOperator.writeToSDcardFile(urlStorageNameOnSDcard, "", "&[s]="+"&uid=" + uid + versionTimeMark() + "taskid=" + taskUId +"&serpindex="+ _SERPIndex + "&url="+ Encode(url) + "&[E]="+ "\n");
		if (!url.startsWith("/")) {
			// it is not relative web address
			mIsHttps = url.startsWith("https://");
		}
		if ((new_scale = webView.getScale()) != old_scale) {
			sendScaleChanged(webView, old_scale, new_scale);
		}
		sendRenderTreeRequest(webView);
	}

	public void sendScaleChanged(WebView webview, float oldScale, float newScale) {
		sendString("ev=WebClientScaleChange&OldScale=" + String.valueOf(oldScale) + "&NewScale=" + String.valueOf(newScale));
		old_scale = newScale;
		sendRenderTreeRequest(webview);
	}

	public void sendTabChange(int oldTabIndex, int newTabIndex, Tab newTab) {
		float new_scale;
		sendString("ev=TabChange&OldTabIndex=" + String.valueOf(oldTabIndex) + "&NewTabIndex=" + String.valueOf(newTabIndex));
		if ((new_scale = newTab.getWebView().getScale()) != old_scale) {
			sendScaleChanged(newTab.getWebView(), old_scale, new_scale);
		}
		sendRenderTreeRequest(newTab.getWebView());
	}

	public void sendScroll(int x, int y) {
		sendString("ev=Scroll&ScrollX=" + String.valueOf(x) + "&ScrollY=" + String.valueOf(y));
	}

	public void sendWebViewDispatchKey(KeyEvent event) {
		sendString("ev=WebViewDispatchKey&Action=" + KeyAction(event) + "&KeyValue=" + String.valueOf(event.getKeyCode()));
	}

	public void sendWebViewSizeChanged(WebView webview, int w, int h, int ow, int oh) {
		sendString("ev=WebViewSizeChange&OldViewWidth=" + String.valueOf(ow) + "&OldViewHeight=" + String.valueOf(oh) + "&NewViewWidth=" + String.valueOf(w)
				+ "&NewViewHeight=" + String.valueOf(h));
		/* First, get the Display from the WindowManager */
		Display display = mWindowManager.getDefaultDisplay();
		if (display.getOrientation() == 0) {// portrait mode
			int max_height_with_softkey = 0;
			if (w == 320) {// on the simulator
				max_height_with_softkey = 233;
			} else if (w == 480) {// on motorola milestone
				max_height_with_softkey = 484;
			}
			if ((h <= max_height_with_softkey) != old_show_softkey) {
				sendShowHideSoftKey(!old_show_softkey);
				old_show_softkey = !old_show_softkey;
			}
		}
	}

	public void sendWebViewOnTouch(WebView webview, MotionEvent ev) {
		float new_scale;
		int size = ev.getPointerCount();
		String s = "ev=WebViewOnTouch&Action=" + MotionAction(ev) + "&TouchPointNum=" + size;
		for (int i = 0; i < size; i++) {
			s += "&vx" + String.valueOf(i) + "=" + ev.getX(i) + "&vy" + String.valueOf(i) + "=" + ev.getY(i) + "&pressure" + String.valueOf(i) + "=" + ev.getPressure(i) + "&touchsize" + String.valueOf(i) + "=" + ev.getSize(i);
		}
		sendString(s);
		// user pinches the screen may also change scale.
		if ((new_scale = webview.getScale()) != old_scale) {
			sendScaleChanged(webview, old_scale, new_scale);
			if (size > 1) {
				float center_x = 0, center_y = 0;
				for (int i = 0; i < size; i++) {
					center_x += ev.getX(i);
					center_y += ev.getY(i);
				}
				center_x /= size;
				center_y /= size;
				String focusedText = getTextFromView(webview, (int) center_x, (int) center_y);
				sendScaleFocusedText(focusedText);
			}
		}
		if (ev.getAction() == MotionEvent.ACTION_DOWN) {
			String focusedText = getTextFromView(webview, (int) ev.getX(), (int) ev.getY());
			sendTouchFocusedText(focusedText);
		}
	}
	
	public void sendSelfReport_Success(String taskuid, int value){
		sendString("ev=ReportTaskSuccess&taskuid=" + taskuid + "&val=" + value);
	}

	public void sendEndCurrentTask(String taskId){
		sendString("ev=EndCurrentTask&taskid=" + taskId);
		SDCardOperator.writeToSDcardFile(urlStorageNameOnSDcard, "","&[s]="+"&uid=" + uid + versionTimeMark() + "taskid=" + taskId +  "&url=EndCurrentTask" + "&[E]="+ "\n" );
	}
	
	public void sendStartNewTask(String taskId){
		sendString("ev=StartNewTask&taskid=" + taskId);
		SDCardOperator.writeToSDcardFile(urlStorageNameOnSDcard, "","&[s]="+"&uid=" + uid + versionTimeMark() + "taskid=" + taskId +  "&url=StartNewTask" + "&[E]="+ "\n" );
	}
	
	public void sendFinishUserStudy(){
		sendString("ev=FinishUserStudy");
		setupUID("unknown");
	}
	
	private void sendTouchFocusedText(String focusedText) {
		if ((focusedText != null) && (focusedText.length() > 0))
			sendString("ev=TouchFocusedText&" + focusedText);
	}

	private void sendScaleFocusedText(String focusedText) {
		if ((focusedText != null) && (focusedText.length() > 0))
			sendString("ev=ScaleFocusedText&" + focusedText);
	}

	public void sendUpdateTextfield(String updatedText) {
		sendString("ev=UpdatedText&EditText=" + Encode(updatedText));
	}

	public void sendOrientation(int old_orientation, int new_orientation) {
		sendString("ev=OrientationChange&OldOrient=" + String.valueOf(old_orientation) + "&NewOrient=" + String.valueOf(new_orientation));
	}

	public void sendCreateNewTab(String url, int index) {
		sendString("ev=CreateNewTab&NewTabIndex=" + String.valueOf(index));
	}

	public void sendRemoveTab(int index) {
		sendString("ev=RemoveTab&OldTabIndex=" + String.valueOf(index));
	}

	public void sendAddBookmark(String url, String name) {
		sendString("ev=AddBookmark&BookmarkUrl=" + Encode(url) + "&BookmarkName=" + Encode(name));
	}

	public void sendRemoveFromBookmarks(String url, String name) {
		sendString("ev=RemoveFromBookmark&BookmarkUrl=" + Encode(url) + "&BookmarkName=" + Encode(name));
	}

	public void sendMenuItemSelected(MenuItem item) {
		sendString("ev=MenuItemSelected&MenuItem=" + Encode(item.getTitle().toString()));
	}

	public void sendShowSearchBox() {
		sendString("ev=ShowSearchBox");
	}

	public void sendWebViewOnTrackball(MotionEvent ev) {
		int size = ev.getPointerCount();
		String s = "ev=WebViewOnTrackball&Action=" + MotionAction(ev) + "&TouchPointNum=" + size;
		for (int i = 0; i < size; i++) {
			s += "&vx" + String.valueOf(i) + "=" + ev.getX(i) + "&vy" + String.valueOf(i) + "=" + ev.getY(i) + "&pressure" + String.valueOf(i) + "=" + ev.getPressure(i) + "&touchsize" + String.valueOf(i) + "=" + ev.getSize(i);
		}
		sendString(s);
	}

	public void sendInputAcceptsText(boolean active) {
		sendString("ev=InputStateChange&InputState=" + String.valueOf(active));
	}

	private void sendShowHideSoftKey(boolean shown) {
		sendString("ev=SoftKeyboard&KeyboardShown=" + String.valueOf(shown));
	}

	public void sendMainMenuState(boolean mOptionsMenuOpen) {
		sendString("ev=MainMenuState&MainMenuShown=" + String.valueOf(mOptionsMenuOpen));
	}

	public void sendGoBackPage() {
		sendString("ev=GoBackPage");
	}

	public void sendLongClick() {
		sendString("ev=LongClick");
	}

	// Can do "Get" and "Post" methods. if "data"==null, do "Get"; else do "Post"
	public String getUrlData(String urlStr, String data) {
		String DataStr = "";
		if (urlStr != null) {
			try {
				URL url = new URL(urlStr);
				URLConnection conn = url.openConnection();
				// Send data
				if ((data != null) && (data.length() > 0)) {
					// post data
					conn.setDoOutput(true);
					OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
					wr.write(data);
					wr.flush();
				}
				// Get the response
				String strLine;
				BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				while ((strLine = rd.readLine()) != null) { // Process, read feedback
					DataStr += strLine;
				}
				rd.close();
			} catch (Exception e) {
			}
		}
		return DataStr;
	}

	public void sendOnCreate() {
		sendString("ev=OnCreate");
	}

	public void sendOnStart() {
		sendString("ev=OnStart&IP=" + getLocalIpAddress());
		shown = true;
	}

	public void sendOnStop() {
		sendString("ev=OnStop", true);// force it to be sent to the server.
		shown = false;
	}

	public void sendOnRestart() {
		sendString("ev=OnReStart");
	}

	public void setTitleBar(TitleBar titlebar) {
		mTitleBar = titlebar;
	}
	
	public void sendOnKeyDown(int keycode){
		sendString("ev=OnKeyDown&KeyValue=" + keycode);
	}

	public void sendRenderTreeRequest(WebView webView) {
		stopParser = true;
		// if (allowGetRenderTree) {
		// allowGetRenderTree = false;
		webView.externalRepresentation(mEPrivateHandler.obtainMessage(EXT_REPRESENTATION_MSG_ID));
		// }
	}

	public void sendOnDraw(WebView webView) {
		mCurrentWebView = webView;
		// webView.setDrawingCacheEnabled(true);
		// Bitmap bm = webView.getDrawingCache();
	}

	public void sendProgressChanged(WebView view, int newProgress) {
		if (newProgress != oldProgress) {
			sendString("ev=OnProgressChange&Progress=" + String.valueOf(newProgress));
			oldProgress = newProgress;
		}
	}
}
