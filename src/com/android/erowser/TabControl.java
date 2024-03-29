/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.erowser;

import java.io.File;
import java.util.ArrayList;
import java.util.Vector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;

class TabControl {
    // Log Tag
    private static final String LOGTAG = "TabControl";
    // Maximum number of tabs.
    static final int MAX_TABS = 8;
    // Static instance of an empty callback.
    private static final WebViewClient mEmptyClient =
            new WebViewClient();
    // Instance of BackgroundChromeClient for background tabs.
    private final BackgroundChromeClient mBackgroundChromeClient =
            new BackgroundChromeClient();
    // Private array of WebViews that are used as tabs.
    private ArrayList<Tab> mTabs = new ArrayList<Tab>(MAX_TABS);
    // Queue of most recently viewed tabs.
    private ArrayList<Tab> mTabQueue = new ArrayList<Tab>(MAX_TABS);
    // Current position in mTabs.
    private int mCurrentTab = -1;
    // A private instance of ErowserActivity to interface with when adding and
    // switching between tabs.
    private final ErowserActivity mActivity;
    // Inflation service for making subwindows.
    private final LayoutInflater mInflateService;
    // Subclass of WebViewClient used in subwindows to notify the main
    // WebViewClient of certain WebView activities.
    private static class SubWindowClient extends WebViewClient {
        // The main WebViewClient.
        private final WebViewClient mClient;

        SubWindowClient(WebViewClient client) {
            mClient = client;
        }
        @Override
        public void doUpdateVisitedHistory(WebView view, String url,
                boolean isReload) {
            mClient.doUpdateVisitedHistory(view, url, isReload);
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mClient.shouldOverrideUrlLoading(view, url);
        }
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                SslError error) {
            mClient.onReceivedSslError(view, handler, error);
        }
        @Override
        public void onReceivedHttpAuthRequest(WebView view,
                HttpAuthHandler handler, String host, String realm) {
            mClient.onReceivedHttpAuthRequest(view, handler, host, realm);
        }
        @Override
        public void onFormResubmission(WebView view, Message dontResend,
                Message resend) {
            mClient.onFormResubmission(view, dontResend, resend);
        }
        @Override
        public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
            mClient.onReceivedError(view, errorCode, description, failingUrl);
        }
        @Override
        public boolean shouldOverrideKeyEvent(WebView view,
                android.view.KeyEvent event) {
            return mClient.shouldOverrideKeyEvent(view, event);
        }
        @Override
        public void onUnhandledKeyEvent(WebView view,
                android.view.KeyEvent event) {
            mClient.onUnhandledKeyEvent(view, event);
        }
    }
    // Subclass of WebChromeClient to display javascript dialogs.
    private class SubWindowChromeClient extends WebChromeClient {
        // This subwindow's tab.
        private final Tab mTab;
        // The main WebChromeClient.
        private final WebChromeClient mClient;

        SubWindowChromeClient(Tab t, WebChromeClient client) {
            mTab = t;
            mClient = client;
        }
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            mClient.onProgressChanged(view, newProgress);
        }
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog,
                boolean userGesture, android.os.Message resultMsg) {
            return mClient.onCreateWindow(view, dialog, userGesture, resultMsg);
        }
        @Override
        public void onCloseWindow(WebView window) {
            if (Erowser.DEBUG && window != mTab.mSubView) {
                throw new AssertionError("Can't close the window");
            }
            mActivity.dismissSubWindow(mTab);
        }
    }
    // Background WebChromeClient for focusing tabs
    private class BackgroundChromeClient extends WebChromeClient {
        @Override
        public void onRequestFocus(WebView view) {
            Tab t = getTabFromView(view);
            if (t != getCurrentTab()) {
                mActivity.switchToTab(getTabIndex(t));
            }
        }
    }

    // Extra saved information for displaying the tab in the picker.
    public static class PickerData {
        String  mUrl;
        String  mTitle;
        Bitmap  mFavicon;
        float   mScale;
        int     mScrollX;
        int     mScrollY;
    }

    /**
     * Private class for maintaining Tabs with a main WebView and a subwindow.
     */
    public class Tab {
        // The Geolocation permissions prompt
        private GeolocationPermissionsPrompt mGeolocationPermissionsPrompt;
        private View mContainer;
        // Main WebView
        private WebView mMainView;
        // Subwindow WebView
        private WebView mSubView;
        // Subwindow container
        private View mSubViewContainer;
        // Subwindow callback
        private SubWindowClient mSubViewClient;
        // Subwindow chrome callback
        private SubWindowChromeClient mSubViewChromeClient;
        // Saved bundle for when we are running low on memory. It contains the
        // information needed to restore the WebView if the user goes back to
        // the tab.
        private Bundle mSavedState;
        // Data used when displaying the tab in the picker.
        private PickerData mPickerData;

        // Parent Tab. This is the Tab that created this Tab, or null
        // if the Tab was created by the UI
        private Tab mParentTab;
        // Tab that constructed by this Tab. This is used when this
        // Tab is destroyed, it clears all mParentTab values in the 
        // children.
        private Vector<Tab> mChildTabs;

        private Boolean mCloseOnExit;
        // Application identifier used to find tabs that another application
        // wants to reuse.
        private String mAppId;
        // Keep the original url around to avoid killing the old WebView if the
        // url has not changed.
        private String mOriginalUrl;

        private ErrorConsoleView mErrorConsole;
        // the lock icon type and previous lock icon type for the tab
        private int mSavedLockIconType;
        private int mSavedPrevLockIconType;

        // Construct a new tab
        private Tab(WebView w, boolean closeOnExit, String appId, String url, Context context) {
            mCloseOnExit = closeOnExit;
            mAppId = appId;
            mOriginalUrl = url;
            mSavedLockIconType = ErowserActivity.LOCK_ICON_UNSECURE;
            mSavedPrevLockIconType = ErowserActivity.LOCK_ICON_UNSECURE;

            // The tab consists of a container view, which contains the main
            // WebView, as well as any other UI elements associated with the tab.
            LayoutInflater factory = LayoutInflater.from(context);
            mContainer = factory.inflate(R.layout.tab, null);

            mGeolocationPermissionsPrompt =
                (GeolocationPermissionsPrompt) mContainer.findViewById(
                    R.id.geolocation_permissions_prompt);

            setWebView(w);
        }

        /**
         * Sets the WebView for this tab, correctly removing the old WebView
         * from the container view.
         */
        public void setWebView(WebView w) {
            if (mMainView == w) {
                return;
            }
            // If the WebView is changing, the page will be reloaded, so any ongoing Geolocation
            // permission requests are void.
            mGeolocationPermissionsPrompt.hide();

            // Just remove the old one.
            FrameLayout wrapper =
                    (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
            wrapper.removeView(mMainView);
            mMainView = w;
        }

        /**
         * This method attaches both the WebView and any sub window to the
         * given content view.
         */
        public void attachTabToContentView(ViewGroup content) {
            if (mMainView == null) {
                return;
            }

            // Attach the WebView to the container and then attach the
            // container to the content view.
            FrameLayout wrapper =
                    (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
            wrapper.addView(mMainView);
            content.addView(mContainer, ErowserActivity.COVER_SCREEN_PARAMS);
            attachSubWindow(content);
        }

        /**
         * Remove the WebView and any sub window from the given content view.
         */
        public void removeTabFromContentView(ViewGroup content) {
            if (mMainView == null) {
                return;
            }

            // Remove the container from the content and then remove the
            // WebView from the container. This will trigger a focus change
            // needed by WebView.
            FrameLayout wrapper =
                    (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
            wrapper.removeView(mMainView);
            content.removeView(mContainer);
            removeSubWindow(content);
        }

        /**
         * Attach the sub window to the content view.
         */
        public void attachSubWindow(ViewGroup content) {
            if (mSubView != null) {
                content.addView(mSubViewContainer,
                        ErowserActivity.COVER_SCREEN_PARAMS);
            }
        }

        /**
         * Remove the sub window from the content view.
         */
        public void removeSubWindow(ViewGroup content) {
            if (mSubView != null) {
                content.removeView(mSubViewContainer);
            }
        }

        /**
         * Return the top window of this tab; either the subwindow if it is not
         * null or the main window.
         * @return The top window of this tab.
         */
        public WebView getTopWindow() {
            if (mSubView != null) {
                return mSubView;
            }
            return mMainView;
        }

        /**
         * Return the main window of this tab. Note: if a tab is freed in the
         * background, this can return null. It is only guaranteed to be 
         * non-null for the current tab.
         * @return The main WebView of this tab.
         */
        public WebView getWebView() {
            return mMainView;
        }

        /**
         * @return The geolocation permissions prompt for this tab.
         */
        public GeolocationPermissionsPrompt getGeolocationPermissionsPrompt() {
            return mGeolocationPermissionsPrompt;
        }

        /**
         * Return the subwindow of this tab or null if there is no subwindow.
         * @return The subwindow of this tab or null.
         */
        public WebView getSubWebView() {
            return mSubView;
        }

        /**
         * Get the url of this tab.  Valid after calling populatePickerData, but
         * before calling wipePickerData, or if the webview has been destroyed.
         * 
         * @return The WebView's url or null.
         */
        public String getUrl() {
            if (mPickerData != null) {
                return mPickerData.mUrl;
            }
            return null;
        }

        /**
         * Get the title of this tab.  Valid after calling populatePickerData, 
         * but before calling wipePickerData, or if the webview has been 
         * destroyed.  If the url has no title, use the url instead.
         * 
         * @return The WebView's title (or url) or null.
         */
        public String getTitle() {
            if (mPickerData != null) {
                return mPickerData.mTitle;
            }
            return null;
        }

        public Bitmap getFavicon() {
            if (mPickerData != null) {
                return mPickerData.mFavicon;
            }
            return null;
        }

        private void setParentTab(Tab parent) {
            mParentTab = parent;
            // This tab may have been freed due to low memory. If that is the
            // case, the parent tab index is already saved. If we are changing
            // that index (most likely due to removing the parent tab) we must
            // update the parent tab index in the saved Bundle.
            if (mSavedState != null) {
                if (parent == null) {
                    mSavedState.remove(PARENTTAB);
                } else {
                    mSavedState.putInt(PARENTTAB, getTabIndex(parent));
                }
            }
        }
        
        /**
         * When a Tab is created through the content of another Tab, then 
         * we associate the Tabs. 
         * @param child the Tab that was created from this Tab
         */
        public void addChildTab(Tab child) {
            if (mChildTabs == null) {
                mChildTabs = new Vector<Tab>();
            }
            mChildTabs.add(child);
            child.setParentTab(this);
        }
        
        private void removeFromTree() {
            // detach the children
            if (mChildTabs != null) {
                for(Tab t : mChildTabs) {
                    t.setParentTab(null);
                }
            }
            
            // Find myself in my parent list
            if (mParentTab != null) {
                mParentTab.mChildTabs.remove(this);
            }
        }
        
        /**
         * If this Tab was created through another Tab, then this method
         * returns that Tab.
         * @return the Tab parent or null
         */
        public Tab getParentTab() {
            return mParentTab;
        }

        /**
         * Return whether this tab should be closed when it is backing out of
         * the first page.
         * @return TRUE if this tab should be closed when exit.
         */
        public boolean closeOnExit() {
            return mCloseOnExit;
        }

        void setLockIconType(int type) {
            mSavedLockIconType = type;
        }

        int getLockIconType() {
            return mSavedLockIconType;
        }

        void setPrevLockIconType(int type) {
            mSavedPrevLockIconType = type;
        }

        int getPrevLockIconType() {
            return mSavedPrevLockIconType;
        }
    };

    // Directory to store thumbnails for each WebView.
    private final File mThumbnailDir;

    /**
     * Construct a new TabControl object that interfaces with the given
     * ErowserActivity instance.
     * @param activity A ErowserActivity instance that TabControl will interface
     *                 with.
     */
    TabControl(ErowserActivity activity) {
        mActivity = activity;
        mInflateService =
                ((LayoutInflater) activity.getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE));
        mThumbnailDir = activity.getDir("thumbnails", 0);
    }

    File getThumbnailDir() {
        return mThumbnailDir;
    }

    ErowserActivity getErowserActivity() {
        return mActivity;
    }

    /**
     * Return the current tab's main WebView. This will always return the main
     * WebView for a given tab and not a subwindow.
     * @return The current tab's WebView.
     */
    WebView getCurrentWebView() {
        Tab t = getTab(mCurrentTab);
        if (t == null) {
            return null;
        }
        return t.mMainView;
    }

    /**
     * Return the current tab's error console. Creates the console if createIfNEcessary
     * is true and we haven't already created the console.
     * @param createIfNecessary Flag to indicate if the console should be created if it has
     *                          not been already.
     * @return The current tab's error console, or null if one has not been created and
     *         createIfNecessary is false.
     */
    ErrorConsoleView getCurrentErrorConsole(boolean createIfNecessary) {
        Tab t = getTab(mCurrentTab);
        if (t == null) {
            return null;
        }

        if (createIfNecessary && t.mErrorConsole == null) {
            t.mErrorConsole = new ErrorConsoleView(mActivity);
            t.mErrorConsole.setWebView(t.mMainView);
        }

        return t.mErrorConsole;
    }

    /**
     * Return the current tab's top-level WebView. This can return a subwindow
     * if one exists.
     * @return The top-level WebView of the current tab.
     */
    WebView getCurrentTopWebView() {
        Tab t = getTab(mCurrentTab);
        if (t == null) {
            return null;
        }
        return t.mSubView != null ? t.mSubView : t.mMainView;
    }

    /**
     * Return the current tab's subwindow if it exists.
     * @return The subwindow of the current tab or null if it doesn't exist.
     */
    WebView getCurrentSubWindow() {
        Tab t = getTab(mCurrentTab);
        if (t == null) {
            return null;
        }
        return t.mSubView;
    }

    /**
     * Return the tab at the specified index.
     * @return The Tab for the specified index or null if the tab does not
     *         exist.
     */
    Tab getTab(int index) {
        if (index >= 0 && index < mTabs.size()) {
            return mTabs.get(index);
        }
        return null;
    }

    /**
     * Return the current tab.
     * @return The current tab.
     */
    Tab getCurrentTab() {
        return getTab(mCurrentTab);
    }

    /**
     * Return the current tab index.
     * @return The current tab index
     */
    int getCurrentIndex() {
        return mCurrentTab;
    }
    
    /**
     * Given a Tab, find it's index
     * @param Tab to find
     * @return index of Tab or -1 if not found
     */
    int getTabIndex(Tab tab) {
        if (tab == null) {
            return -1;
        }
        return mTabs.indexOf(tab);
    }

    /**
     * Create a new tab.
     * @return The newly createTab or null if we have reached the maximum
     *         number of open tabs.
     */
    Tab createNewTab(boolean closeOnExit, String appId, String url) {
        int size = mTabs.size();
        // Return false if we have maxed out on tabs
        if (MAX_TABS == size) {
            return null;
        }
        final WebView w = createNewWebView();

        // Create a new tab and add it to the tab list
        Tab t = new Tab(w, closeOnExit, appId, url, mActivity);
        mTabs.add(t);
        // Initially put the tab in the background.
        putTabInBackground(t);
        EventMonitor.getInstance().sendCreateNewTab(url, mTabs.indexOf(t));//shuaiyuan added
        return t;
    }

    /**
     * Create a new tab with default values for closeOnExit(false),
     * appId(null), and url(null).
     */
    Tab createNewTab() {
        return createNewTab(false, null, null);
    }

    /**
     * Remove the tab from the list. If the tab is the current tab shown, the
     * last created tab will be shown.
     * @param t The tab to be removed.
     */
    boolean removeTab(Tab t) {
        if (t == null) {
            return false;
        }
        // Only remove the tab if it is the current one.
        if (getCurrentTab() == t) {
            putTabInBackground(t);
        }

        // Only destroy the WebView if it still exists.
        if (t.mMainView != null) {
            // Take down the sub window.
            dismissSubWindow(t);
            // Remove the WebView's settings from the ErowserSettings list of
            // observers.
            ErowserSettings.getInstance().deleteObserver(
                    t.mMainView.getSettings());
            WebView w = t.mMainView;
            t.setWebView(null);
            // Destroy the main view
            w.destroy();
        }
        // clear it's references to parent and children
        t.removeFromTree();
        
        EventMonitor.getInstance().sendRemoveTab(mTabs.indexOf(t));//shuaiyuan added        
        // Remove it from our list of tabs.
        mTabs.remove(t);

        // The tab indices have shifted, update all the saved state so we point
        // to the correct index.
        for (Tab tab : mTabs) {
            if (tab.mChildTabs != null) {
                for (Tab child : tab.mChildTabs) {
                    child.setParentTab(tab);
                }
            }
        }


        // This tab may have been pushed in to the background and then closed.
        // If the saved state contains a picture file, delete the file.
        if (t.mSavedState != null) {
            if (t.mSavedState.containsKey(CURRPICTURE)) {
                new File(t.mSavedState.getString(CURRPICTURE)).delete();
            }
        }

        // Remove it from the queue of viewed tabs.
        mTabQueue.remove(t);
        mCurrentTab = -1;
        return true;
    }

    /**
     * Clear the back/forward list for all the current tabs.
     */
    void clearHistory() {
        int size = getTabCount();
        for (int i = 0; i < size; i++) {
            Tab t = mTabs.get(i);
            // TODO: if a tab is freed due to low memory, its history is not
            // cleared here.
            if (t.mMainView != null) {
                t.mMainView.clearHistory();
            }
            if (t.mSubView != null) {
                t.mSubView.clearHistory();
            }
        }
    }

    /**
     * Destroy all the tabs and subwindows
     */
    void destroy() {
        ErowserSettings s = ErowserSettings.getInstance();
        for (Tab t : mTabs) {
            if (t.mMainView != null) {
                dismissSubWindow(t);
                s.deleteObserver(t.mMainView.getSettings());
                WebView w = t.mMainView;
                t.setWebView(null);
                w.destroy();
            }
        }
        mTabs.clear();
        mTabQueue.clear();
    }

    /**
     * Returns the number of tabs created.
     * @return The number of tabs created.
     */
    int getTabCount() {
        return mTabs.size();
    }

    // Used for saving and restoring each Tab
    private static final String WEBVIEW = "webview";
    private static final String NUMTABS = "numTabs";
    private static final String CURRTAB = "currentTab";
    private static final String CURRURL = "currentUrl";
    private static final String CURRTITLE = "currentTitle";
    private static final String CURRPICTURE = "currentPicture";
    private static final String CLOSEONEXIT = "closeonexit";
    private static final String PARENTTAB = "parentTab";
    private static final String APPID = "appid";
    private static final String ORIGINALURL = "originalUrl";

    /**
     * Save the state of all the Tabs.
     * @param outState The Bundle to save the state to.
     */
    void saveState(Bundle outState) {
        final int numTabs = getTabCount();
        outState.putInt(NUMTABS, numTabs);
        final int index = getCurrentIndex();
        outState.putInt(CURRTAB, (index >= 0 && index < numTabs) ? index : 0);
        for (int i = 0; i < numTabs; i++) {
            final Tab t = getTab(i);
            if (saveState(t)) {
                outState.putBundle(WEBVIEW + i, t.mSavedState);
            }
        }
    }

    /**
     * Restore the state of all the tabs.
     * @param inState The saved state of all the tabs.
     * @return True if there were previous tabs that were restored. False if
     *         there was no saved state or restoring the state failed.
     */
    boolean restoreState(Bundle inState) {
        final int numTabs = (inState == null)
                ? -1 : inState.getInt(NUMTABS, -1);
        if (numTabs == -1) {
            return false;
        } else {
            final int currentTab = inState.getInt(CURRTAB, -1);
            for (int i = 0; i < numTabs; i++) {
                if (i == currentTab) {
                    Tab t = createNewTab();
                    // Me must set the current tab before restoring the state
                    // so that all the client classes are set.
                    setCurrentTab(t);
                    if (!restoreState(inState.getBundle(WEBVIEW + i), t)) {
                        Log.w(LOGTAG, "Fail in restoreState, load home page.");
                        t.mMainView.loadUrl(ErowserSettings.getInstance()
                                .getHomePage());
                    }
                } else {
                    // Create a new tab and don't restore the state yet, add it
                    // to the tab list
                    Tab t = new Tab(null, false, null, null, mActivity);
                    t.mSavedState = inState.getBundle(WEBVIEW + i);
                    if (t.mSavedState != null) {
                        populatePickerDataFromSavedState(t);
                        // Need to maintain the app id and original url so we
                        // can possibly reuse this tab.
                        t.mAppId = t.mSavedState.getString(APPID);
                        t.mOriginalUrl = t.mSavedState.getString(ORIGINALURL);
                    }
                    mTabs.add(t);
                    mTabQueue.add(t);
                }
            }
            // Rebuild the tree of tabs. Do this after all tabs have been
            // created/restored so that the parent tab exists.
            for (int i = 0; i < numTabs; i++) {
                final Bundle b = inState.getBundle(WEBVIEW + i);
                final Tab t = getTab(i);
                if (b != null && t != null) {
                    final int parentIndex = b.getInt(PARENTTAB, -1);
                    if (parentIndex != -1) {
                        final Tab parent = getTab(parentIndex);
                        if (parent != null) {
                            parent.addChildTab(t);
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Free the memory in this order, 1) free the background tab; 2) free the
     * WebView cache;
     */
    void freeMemory() {
        if (getTabCount() == 0) return;

        // free the least frequently used background tab
        Tab t = getLeastUsedTab(getCurrentTab());
        if (t != null) {
            Log.w(LOGTAG, "Free a tab in the erowser");
            freeTab(t);
            // force a gc
            System.gc();
            return;
        }

        // free the WebView's unused memory (this includes the cache)
        Log.w(LOGTAG, "Free WebView's unused memory and cache");
        WebView view = getCurrentWebView();
        if (view != null) {
            view.freeMemory();
        }
        // force a gc
        System.gc();
    }

    private Tab getLeastUsedTab(Tab current) {
        // Don't do anything if we only have 1 tab or if the current tab is
        // null.
        if (getTabCount() == 1 || current == null) {
            return null;
        }

        // Rip through the queue starting at the beginning and teardown the
        // next available tab.
        Tab t = null;
        int i = 0;
        final int queueSize = mTabQueue.size();
        if (queueSize == 0) {
            return null;
        }
        do {
            t = mTabQueue.get(i++);
        } while (i < queueSize
                && ((t != null && t.mMainView == null)
                    || t == current.mParentTab));

        // Don't do anything if the last remaining tab is the current one or if
        // the last tab has been freed already.
        if (t == current || t.mMainView == null) {
            return null;
        }

        return t;
    }

    private void freeTab(Tab t) {
        // Store the WebView's state.
        saveState(t);

        // Tear down the tab.
        dismissSubWindow(t);
        // Remove the WebView's settings from the ErowserSettings list of
        // observers.
        ErowserSettings.getInstance().deleteObserver(t.mMainView.getSettings());
        WebView w = t.mMainView;
        t.setWebView(null);
        w.destroy();
    }

    /**
     * Create a new subwindow unless a subwindow already exists.
     * @return True if a new subwindow was created. False if one already exists.
     */
    void createSubWindow() {
        Tab t = getTab(mCurrentTab);
        if (t != null && t.mSubView == null) {
            final View v = mInflateService.inflate(R.layout.erowser_subwindow, null);
            final WebView w = (WebView) v.findViewById(R.id.webview);
            w.setMapTrackballToArrowKeys(false); // use trackball directly
            final SubWindowClient subClient =
                    new SubWindowClient(mActivity.getWebViewClient());
            final SubWindowChromeClient subChromeClient =
                    new SubWindowChromeClient(t,
                            mActivity.getWebChromeClient());
            w.setWebViewClient(subClient);
            w.setWebChromeClient(subChromeClient);
            w.setDownloadListener(mActivity);
            w.setOnCreateContextMenuListener(mActivity);
            final ErowserSettings s = ErowserSettings.getInstance();
            s.addObserver(w.getSettings()).update(s, null);
            t.mSubView = w;
            t.mSubViewClient = subClient;
            t.mSubViewChromeClient = subChromeClient;
            // FIXME: I really hate having to know the name of the view
            // containing the webview.
            t.mSubViewContainer = v.findViewById(R.id.subwindow_container);
            final ImageButton cancel =
                    (ImageButton) v.findViewById(R.id.subwindow_close);
            cancel.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        subChromeClient.onCloseWindow(w);
                    }
                });
        }
    }

    /**
     * Show the tab that contains the given WebView.
     * @param view The WebView used to find the tab.
     */
    Tab getTabFromView(WebView view) {
        final int size = getTabCount();
        for (int i = 0; i < size; i++) {
            final Tab t = getTab(i);
            if (t.mSubView == view || t.mMainView == view) {
                return t;
            }
        }
        return null;
    }

    /**
     * Return the tab with the matching application id.
     * @param id The application identifier.
     */
    Tab getTabFromId(String id) {
        if (id == null) {
            return null;
        }
        final int size = getTabCount();
        for (int i = 0; i < size; i++) {
            final Tab t = getTab(i);
            if (id.equals(t.mAppId)) {
                return t;
            }
        }
        return null;
    }

    // This method checks if a non-app tab (one created within the erowser)
    // matches the given url.
    private boolean tabMatchesUrl(Tab t, String url) {
        if (t.mAppId != null) {
            return false;
        } else if (t.mMainView == null) {
            return false;
        } else if (url.equals(t.mMainView.getUrl()) ||
                url.equals(t.mMainView.getOriginalUrl())) {
            return true;
        }
        return false;
    }

    /**
     * Return the tab that has no app id associated with it and the url of the
     * tab matches the given url.
     * @param url The url to search for.
     */
    Tab findUnusedTabWithUrl(String url) {
        if (url == null) {
            return null;
        }
        // Check the current tab first.
        Tab t = getCurrentTab();
        if (t != null && tabMatchesUrl(t, url)) {
            return t;
        }
        // Now check all the rest.
        final int size = getTabCount();
        for (int i = 0; i < size; i++) {
            t = getTab(i);
            if (tabMatchesUrl(t, url)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Recreate the main WebView of the given tab. Returns true if the WebView
     * was deleted.
     */
    boolean recreateWebView(Tab t, String url) {
        final WebView w = t.mMainView;
        if (w != null) {
            if (url != null && url.equals(t.mOriginalUrl)) {
                // The original url matches the current url. Just go back to the
                // first history item so we can load it faster than if we
                // rebuilt the WebView.
                final WebBackForwardList list = w.copyBackForwardList();
                if (list != null) {
                    w.goBackOrForward(-list.getCurrentIndex());
                    w.clearHistory(); // maintains the current page.
                    return false;
                }
            }
            // Remove the settings object from the global settings and destroy
            // the WebView.
            ErowserSettings.getInstance().deleteObserver(
                    t.mMainView.getSettings());
            t.mMainView.destroy();
        }
        // Create a new WebView. If this tab is the current tab, we need to put
        // back all the clients so force it to be the current tab.
        t.setWebView(createNewWebView());
        if (getCurrentTab() == t) {
            setCurrentTab(t, true);
        }
        // Clear the saved state except for the app id and close-on-exit
        // values.
        t.mSavedState = null;
        t.mPickerData = null;
        // Save the new url in order to avoid deleting the WebView.
        t.mOriginalUrl = url;
        return true;
    }

    /**
     * Creates a new WebView and registers it with the global settings.
     */
    private WebView createNewWebView() {
        // Create a new WebView
        ErowserWebView w = new ErowserWebView(mActivity);
        w.setScrollbarFadingEnabled(true);
        w.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        w.setMapTrackballToArrowKeys(false); // use trackball directly
        // Enable the built-in zoom
        w.getSettings().setBuiltInZoomControls(true);
        // Add this WebView to the settings observer list and update the
        // settings
        final ErowserSettings s = ErowserSettings.getInstance();
        s.addObserver(w.getSettings()).update(s, null);

        // pick a default
        if (false) {
            MeshTracker mt = new MeshTracker(2);
            Paint paint = new Paint();
            Bitmap bm = BitmapFactory.decodeResource(mActivity.getResources(),
                                         R.drawable.pattern_carbon_fiber_dark);
            paint.setShader(new BitmapShader(bm, Shader.TileMode.REPEAT,
                                             Shader.TileMode.REPEAT));
            mt.setBGPaint(paint);
            w.setDragTracker(mt);
        }
        return w;
    }

    /**
     * Put the current tab in the background and set newTab as the current tab.
     * @param newTab The new tab. If newTab is null, the current tab is not
     *               set.
     */
    boolean setCurrentTab(Tab newTab) {
        return setCurrentTab(newTab, false);
    }

    /*package*/ void pauseCurrentTab() {
        Tab t = getCurrentTab();
        if (t != null) {
            t.mMainView.onPause();
            if (t.mSubView != null) {
                t.mSubView.onPause();
            }
        }
    }

    /*package*/ void resumeCurrentTab() {
        Tab t = getCurrentTab();
        if (t != null) {
            t.mMainView.onResume();
            if (t.mSubView != null) {
                t.mSubView.onResume();
            }
        }
    }

    private void putViewInForeground(WebView v, WebViewClient vc,
                                     WebChromeClient cc) {
        v.setWebViewClient(vc);
        v.setWebChromeClient(cc);
        v.setOnCreateContextMenuListener(mActivity);
        v.setDownloadListener(mActivity);
        v.onResume();
    }

    private void putViewInBackground(WebView v) {
        // Set an empty callback so that default actions are not triggered.
        v.setWebViewClient(mEmptyClient);
        v.setWebChromeClient(mBackgroundChromeClient);
        v.setOnCreateContextMenuListener(null);
        // Leave the DownloadManager attached so that downloads can start in
        // a non-active window. This can happen when going to a site that does
        // a redirect after a period of time. The user could have switched to
        // another tab while waiting for the download to start.
        v.setDownloadListener(mActivity);
        v.onPause();
    }

    /**
     * If force is true, this method skips the check for newTab == current.
     */
    private boolean setCurrentTab(Tab newTab, boolean force) {
        Tab current = getTab(mCurrentTab);
        if (current == newTab && !force) {
            return true;
        }
        if (current != null) {
            // Remove the current WebView and the container of the subwindow
            putTabInBackground(current);
        }

        if (newTab == null) {
            return false;
        }

        // Move the newTab to the end of the queue
        int index = mTabQueue.indexOf(newTab);
        if (index != -1) {
            mTabQueue.remove(index);
        }
        mTabQueue.add(newTab);

        WebView mainView;

        // Display the new current tab
        mCurrentTab = mTabs.indexOf(newTab);
        mainView = newTab.mMainView;
        boolean needRestore = (mainView == null);
        if (needRestore) {
            // Same work as in createNewTab() except don't do new Tab()
            mainView = createNewWebView();
            newTab.setWebView(mainView);
        }
        putViewInForeground(mainView, mActivity.getWebViewClient(),
                            mActivity.getWebChromeClient());
        // Add the subwindow if it exists
        if (newTab.mSubViewContainer != null) {
            putViewInForeground(newTab.mSubView, newTab.mSubViewClient,
                                newTab.mSubViewChromeClient);
        }
        if (needRestore) {
            // Have to finish setCurrentTab work before calling restoreState
            if (!restoreState(newTab.mSavedState, newTab)) {
                mainView.loadUrl(ErowserSettings.getInstance().getHomePage());
            }
        }
        EventMonitor.getInstance().sendTabChange(mTabs.indexOf(current), mCurrentTab, newTab);//shuaiyuan added. I know the names are so confusing  
        return true;
    }

    /*
     * Put the tab in the background using all the empty/background clients.
     */
    private void putTabInBackground(Tab t) {
        putViewInBackground(t.mMainView);
        if (t.mSubView != null) {
            putViewInBackground(t.mSubView);
        }
    }

    /*
     * Dismiss the subwindow for the given tab.
     */
    void dismissSubWindow(Tab t) {
        if (t != null && t.mSubView != null) {
            ErowserSettings.getInstance().deleteObserver(
                    t.mSubView.getSettings());
            t.mSubView.destroy();
            t.mSubView = null;
            t.mSubViewContainer = null;
        }
    }

    /**
     * Ensure that Tab t has data to display in the tab picker.
     * @param  t   Tab to populate.
     */
    /* package */ void populatePickerData(Tab t) {
        if (t == null) {
            return;
        }

        // mMainView == null indicates that the tab has been freed.
        if (t.mMainView == null) {
            populatePickerDataFromSavedState(t);
            return;
        }

        // FIXME: The only place we cared about subwindow was for 
        // bookmarking (i.e. not when saving state). Was this deliberate?
        final WebBackForwardList list = t.mMainView.copyBackForwardList();
        final WebHistoryItem item =
                list != null ? list.getCurrentItem() : null;
        populatePickerData(t, item);
    }

    // Create the PickerData and populate it using the saved state of the tab.
    private void populatePickerDataFromSavedState(Tab t) {
        if (t.mSavedState == null) {
            return;
        }

        final PickerData data = new PickerData();
        final Bundle state = t.mSavedState;
        data.mUrl = state.getString(CURRURL);
        data.mTitle = state.getString(CURRTITLE);
        // XXX: These keys are from WebView.savePicture so if they change, this
        // will break.
        data.mScale = state.getFloat("scale", 1.0f);
        data.mScrollX = state.getInt("scrollX", 0);
        data.mScrollY = state.getInt("scrollY", 0);

        // Set the tab's picker data.
        t.mPickerData = data;
    }

    // Populate the picker data using the given history item and the current
    // top WebView.
    private void populatePickerData(Tab t, WebHistoryItem item) {
        final PickerData data = new PickerData();
        if (item != null) {
            data.mUrl = item.getUrl();
            data.mTitle = item.getTitle();
            data.mFavicon = item.getFavicon();
            if (data.mTitle == null) {
                data.mTitle = data.mUrl;
            }
        }
        // We want to display the top window in the tab picker but use the url
        // and title of the main window.
        final WebView w = t.getTopWindow();
        data.mScale = w.getScale();
        data.mScrollX = w.getScrollX();
        data.mScrollY = w.getScrollY();

        t.mPickerData = data;
    }
    
    /**
     * Clean up the data for all tabs.
     */
    /* package */ void wipeAllPickerData() {
        int size = getTabCount();
        for (int i = 0; i < size; i++) {
            final Tab t = getTab(i);
            if (t != null && t.mSavedState == null) {
                t.mPickerData = null;
            }
        }
    }

    /*
     * Save the state for an individual tab.
     */
    private boolean saveState(Tab t) {
        if (t != null) {
            final WebView w = t.mMainView;
            // If the WebView is null it means we ran low on memory and we
            // already stored the saved state in mSavedState.
            if (w == null) {
                return true;
            }
            final Bundle b = new Bundle();
            final WebBackForwardList list = w.saveState(b);
            if (list != null) {
                final File f = new File(mThumbnailDir, w.hashCode()
                        + "_pic.save");
                if (w.savePicture(b, f)) {
                    b.putString(CURRPICTURE, f.getPath());
                }
            }

            // Store some extra info for displaying the tab in the picker.
            final WebHistoryItem item =
                    list != null ? list.getCurrentItem() : null;
            populatePickerData(t, item);

            // XXX: WebView.savePicture stores the scale and scroll positions
            // in the bundle so we don't have to do it here.
            final PickerData data = t.mPickerData;
            if (data.mUrl != null) {
                b.putString(CURRURL, data.mUrl);
            }
            if (data.mTitle != null) {
                b.putString(CURRTITLE, data.mTitle);
            }
            b.putBoolean(CLOSEONEXIT, t.mCloseOnExit);
            if (t.mAppId != null) {
                b.putString(APPID, t.mAppId);
            }
            if (t.mOriginalUrl != null) {
                b.putString(ORIGINALURL, t.mOriginalUrl);
            }

            // Remember the parent tab so the relationship can be restored.
            if (t.mParentTab != null) {
                b.putInt(PARENTTAB, getTabIndex(t.mParentTab));
            }

            // Remember the saved state.
            t.mSavedState = b;
            return true;
        }
        return false;
    }

    /*
     * Restore the state of the tab.
     */
    private boolean restoreState(Bundle b, Tab t) {
        if (b == null) {
            return false;
        }
        // Restore the internal state even if the WebView fails to restore.
        // This will maintain the app id, original url and close-on-exit values.
        t.mSavedState = null;
        t.mPickerData = null;
        t.mCloseOnExit = b.getBoolean(CLOSEONEXIT);
        t.mAppId = b.getString(APPID);
        t.mOriginalUrl = b.getString(ORIGINALURL);

        final WebView w = t.mMainView;
        final WebBackForwardList list = w.restoreState(b);
        if (list == null) {
            return false;
        }
        if (b.containsKey(CURRPICTURE)) {
            final File f = new File(b.getString(CURRPICTURE));
            w.restorePicture(b, f);
            f.delete();
        }
        return true;
    }
}
