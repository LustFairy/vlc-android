/*****************************************************************************
 * MainActivity.java
 *****************************************************************************
 * Copyright © 2011-2014 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.gui;

import android.annotation.TargetApi;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FilterQueryProvider;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.vlc.BuildConfig;
import org.videolan.vlc.PlaybackService;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.gui.audio.AudioBrowserFragment;
import org.videolan.vlc.gui.browser.BaseBrowserFragment;
import org.videolan.vlc.gui.browser.FileBrowserFragment;
import org.videolan.vlc.gui.browser.MediaBrowserFragment;
import org.videolan.vlc.gui.browser.NetworkBrowserFragment;
import org.videolan.vlc.gui.helpers.SearchSuggestionsAdapter;
import org.videolan.vlc.gui.helpers.UiTools;
import org.videolan.vlc.gui.network.MRLPanelFragment;
import org.videolan.vlc.gui.preferences.PreferencesActivity;
import org.videolan.vlc.gui.video.VideoGridFragment;
import org.videolan.vlc.gui.video.VideoListAdapter;
import org.videolan.vlc.gui.view.HackyDrawerLayout;
import org.videolan.vlc.interfaces.IRefreshable;
import org.videolan.vlc.interfaces.ISortable;
import org.videolan.vlc.media.MediaDatabase;
import org.videolan.vlc.media.MediaLibrary;
import org.videolan.vlc.media.MediaUtils;
import org.videolan.vlc.util.Permissions;
import org.videolan.vlc.util.Util;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.WeakHandler;

public class MainActivity extends AudioPlayerContainerActivity implements SearchSuggestionsAdapter.SuggestionDisplay, FilterQueryProvider, NavigationView.OnNavigationItemSelectedListener {
    public final static String TAG = "VLC/MainActivity";

    private static final String PREF_FIRST_RUN = "first_run";

    private static final int ACTIVITY_RESULT_PREFERENCES = 1;
    private static final int ACTIVITY_RESULT_OPEN = 2;
    private static final int ACTIVITY_SHOW_INFOLAYOUT = 2;
    private static final int ACTIVITY_SHOW_PROGRESSBAR = 3;
    private static final int ACTIVITY_HIDE_PROGRESSBAR = 4;
    private static final int ACTIVITY_SHOW_TEXTINFO = 5;


    MediaLibrary mMediaLibrary;

    private HackyDrawerLayout mDrawerLayout;
    private NavigationView mNavigationView;
    private ActionBarDrawerToggle mDrawerToggle;

    private View mInfoLayout;
    private ProgressBar mInfoProgress;
    private TextView mInfoText;
    private int mCurrentFragment;


    private int mVersionNumber = -1;
    private boolean mFirstRun = false;
    private boolean mScanNeeded = false;

    private Handler mHandler = new MainActivityHandler(this);
    private int mFocusedPrior = 0;
    private int mActionBarIconId = -1;
    Menu mMenu;
    private SearchView mSearchView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!VLCInstance.testCompatibleCPU(this)) {
            finish();
            return;
        }
        /* Enable the indeterminate progress feature */
        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        /* Get the current version from package */
        mVersionNumber = BuildConfig.VERSION_CODE;

        /* Check if it's the first run */
        mFirstRun = mSettings.getInt(PREF_FIRST_RUN, -1) != mVersionNumber;
        if (mFirstRun) {
            Editor editor = mSettings.edit();
            editor.putInt(PREF_FIRST_RUN, mVersionNumber);
            Util.commitPreferences(editor);
        }

        Permissions.checkReadStoragePermission(this, false);

        mMediaLibrary = MediaLibrary.getInstance();
        if (mMediaLibrary.getMediaItems().isEmpty()) {
            if (mSettings.getBoolean(PreferencesActivity.AUTO_RESCAN, true))
                mMediaLibrary.scanMediaItems();
            else
                mMediaLibrary.loadMedaItems();
        }

        /*** Start initializing the UI ***/

        setContentView(R.layout.main);

        mDrawerLayout = (HackyDrawerLayout) findViewById(R.id.root_container);
        setupNavigationView();

        initAudioPlayerContainerActivity();

        if (savedInstanceState != null){
            mCurrentFragment = savedInstanceState.getInt("current");
            if (mCurrentFragment > 0)
                mNavigationView.setCheckedItem(mCurrentFragment);
        }


        /* Initialize UI variables */
        mInfoLayout = findViewById(R.id.info_layout);
        mInfoProgress = (ProgressBar) findViewById(R.id.info_progress);
        mInfoText = (TextView) findViewById(R.id.info_text);

        /* Set up the action bar */
        prepareActionBar();

        /* Set up the sidebar click listener
         * no need to invalidate menu for now */
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.drawer_open, R.string.drawer_close){
            @Override
            public void onDrawerClosed(View drawerView) {
                super.onDrawerClosed(drawerView);
                if (getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder) instanceof MediaBrowserFragment)
                    ((MediaBrowserFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder)).setReadyToDisplay(true);
            }
        };

        // Set the drawer toggle as the DrawerListener
        mDrawerLayout.setDrawerListener(mDrawerToggle);
        // set a custom shadow that overlays the main content when the drawer opens
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        if (mFirstRun) {
            /*
             * The sliding menu is automatically opened when the user closes
             * the info dialog. If (for any reason) the dialog is not shown,
             * open the menu after a short delay.
             */
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mDrawerLayout.openDrawer(mNavigationView);
                }
            }, 500);
        }

        /* Reload the latest preferences */
        reloadPreferences();
    }

    private void setupNavigationView() {
        mNavigationView = (NavigationView) findViewById(R.id.navigation);
        mNavigationView.setNavigationItemSelectedListener(this);
        if (TextUtils.equals(BuildConfig.FLAVOR_target, "chrome")) {
            MenuItem item = mNavigationView.getMenu().findItem(R.id.nav_directories);
            item.setTitle(R.string.open);
        }

        if (BuildConfig.DEBUG) {
            mNavigationView.getMenu().findItem(R.id.nav_history).setVisible(true);
            mNavigationView.getMenu().findItem(R.id.nav_network).setVisible(true);
        }

        if (AndroidUtil.isLolliPopOrLater())
            mNavigationView.setPadding(0, mNavigationView.getPaddingTop()/2, 0, 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case Permissions.PERMISSION_STORAGE_TAG:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    forceRefresh(getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder));
                } else {
                    Permissions.showStoragePermissionDialog(this, false);
                }
                return;
            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        mDrawerToggle.syncState();
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void prepareActionBar() {
        mActionBar.setDisplayHomeAsUpEnabled(true);
        mActionBar.setHomeButtonEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        /* Load media items from database and storage */
        if (mScanNeeded)
            mMediaLibrary.scanMediaItems();
        if (mSlidingPane.getState() == mSlidingPane.STATE_CLOSED)
            mActionBar.hide();
        mNavigationView.setCheckedItem(mCurrentFragment);
        mCurrentFragment = mSettings.getInt("fragment_id", R.id.nav_video);
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        // Figure out if currently-loaded fragment is a top-level fragment.
        Fragment current = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_placeholder);

        /**
         * Restore the last view.
         *
         * Replace:
         * - null fragments (freshly opened Activity)
         * - Wrong fragment open AND currently displayed fragment is a top-level fragment
         *
         * Do not replace:
         * - Non-sidebar fragments.
         * It will try to remove() the currently displayed fragment
         * (i.e. tracks) and replace it with a blank screen. (stuck menu bug)
         */
        if (current == null) {
            mNavigationView.setCheckedItem(mCurrentFragment);
            Fragment ff = getFragment(mCurrentFragment);
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.fragment_placeholder, ff, getTag(mCurrentFragment));
            ft.commit();
        }
    }

    /**
     * Stop audio player and save opened tab
     */
    @Override
    protected void onPause() {
        super.onPause();
        /* Check for an ongoing scan that needs to be resumed during onResume */
        mScanNeeded = mMediaLibrary.isWorking();
        /* Stop scanning for files */
        mMediaLibrary.stop();
        /* Save the tab status in pref */
        SharedPreferences.Editor editor = mSettings.edit();
        editor.putInt("fragment_id", mCurrentFragment);
        Util.commitPreferences(editor);

        mFocusedPrior = 0;
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current", mCurrentFragment);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        /* Reload the latest preferences */
        reloadPreferences();
    }

    @Override
    public void onBackPressed() {
        /* Close the menu first */
        if(mDrawerLayout.isDrawerOpen(mNavigationView)) {
            if (mFocusedPrior != 0)
                requestFocusOnSearch();
            mDrawerLayout.closeDrawer(mNavigationView);
            return;
        }

        /* Slide down the audio player if it is shown entirely. */
        if (slideDownAudioPlayer())
            return;

        // If it's the directory view, a "backpressed" action shows a parent.
        if (mCurrentFragment == R.id.nav_network || mCurrentFragment == R.id.nav_directories){
            BaseBrowserFragment browserFragment = (BaseBrowserFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_placeholder);
            if (browserFragment != null) {
                browserFragment.goBack();
                return;
            }
        }
        finish();
    }

    private Fragment getFragment(int id)
    {
        Fragment frag = getSupportFragmentManager().findFragmentByTag(getTag(id));
        if (frag != null)
            return frag;
        switch (id) {
            case R.id.nav_audio:
                return new AudioBrowserFragment();
            case R.id.nav_directories:
                return new FileBrowserFragment();
            case R.id.nav_history:
                return new HistoryFragment();
            case R.id.nav_mrl:
                return new MRLPanelFragment();
            case R.id.nav_network:
                return new NetworkBrowserFragment();
            default:
                return new VideoGridFragment();
        }
    }

    private static void ShowFragment(FragmentActivity activity, String tag, Fragment fragment, String previous) {
        if (fragment == null) {
            Log.e(TAG, "Cannot show a null fragment, ShowFragment("+tag+") aborted.");
            return;
        }

        FragmentManager fm = activity.getSupportFragmentManager();

        //abort if fragment is already the current one
        Fragment current = fm.findFragmentById(R.id.fragment_placeholder);
        if(current != null && current.getTag().equals(tag))
            return;

        //try to pop back if the fragment is already on the backstack
        if (fm.popBackStackImmediate(tag, 0))
            return;

        //fragment is not there yet, spawn a new one
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.anim_enter_right, R.anim.anim_leave_left, R.anim.anim_enter_left, R.anim.anim_leave_right);
        ft.replace(R.id.fragment_placeholder, fragment, tag);
        ft.addToBackStack(previous);
        ft.commit();
    }

    /**
     * Show a secondary fragment.
     */
    public void showSecondaryFragment(String fragmentTag) {
        showSecondaryFragment(fragmentTag, null);
    }

    public void showSecondaryFragment(String fragmentTag, String param) {
        Intent i = new Intent(this, SecondaryActivity.class);
        i.putExtra("fragment", fragmentTag);
        if (param != null)
            i.putExtra("param", param);
        startActivity(i);
        // Slide down the audio player if needed.
        slideDownAudioPlayer();
    }

    /** Create menu from XML
     */
    @TargetApi(Build.VERSION_CODES.FROYO)
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mMenu = menu;
        /* Note: on Android 3.0+ with an action bar this method
         * is called while the view is created. This can happen
         * any time after onCreate.
         */
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.media_library, menu);

        if (AndroidUtil.isFroyoOrLater()) {
            SearchManager searchManager =
                    (SearchManager) VLCApplication.getAppContext().getSystemService(Context.SEARCH_SERVICE);
            mSearchView = (SearchView) MenuItemCompat.getActionView(menu.findItem(R.id.ml_menu_search));
            mSearchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
            mSearchView.setQueryHint(getString(R.string.search_hint));
            SearchSuggestionsAdapter searchSuggestionsAdapter = new SearchSuggestionsAdapter(this, null);
            searchSuggestionsAdapter.setFilterQueryProvider(this);
            mSearchView.setSuggestionsAdapter(searchSuggestionsAdapter);
        } else
            menu.findItem(R.id.ml_menu_search).setVisible(false);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu (Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (menu == null)
            return false;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder);
        MenuItem item;
        // Disable the sort option if we can't use it on the current fragment.
        if (current == null || !(current instanceof ISortable)) {
            item = menu.findItem(R.id.ml_menu_sortby);
            if (item == null)
                return false;
            item.setEnabled(false);
            item.setVisible(false);
        } else {
            ISortable sortable = (ISortable) current;
            item = menu.findItem(R.id.ml_menu_sortby);
            if (item == null)
                return false;
            item.setEnabled(true);
            item.setVisible(true);
            item = menu.findItem(R.id.ml_menu_sortby_name);
            if (sortable.sortDirection(VideoListAdapter.SORT_BY_TITLE) == 1)
                item.setTitle(R.string.sortby_name_desc);
            else
                item.setTitle(R.string.sortby_name);
            item = menu.findItem(R.id.ml_menu_sortby_length);
            if (sortable.sortDirection(VideoListAdapter.SORT_BY_LENGTH) == 1)
                item.setTitle(R.string.sortby_length_desc);
            else
                item.setTitle(R.string.sortby_length);
            item = menu.findItem(R.id.ml_menu_sortby_date);
            if (sortable.sortDirection(VideoListAdapter.SORT_BY_DATE) == 1)
                item.setTitle(R.string.sortby_date_desc);
            else
                item.setTitle(R.string.sortby_date);
        }

        boolean networkSave = current instanceof NetworkBrowserFragment && !((NetworkBrowserFragment)current).isRootDirectory();
        if (networkSave) {
            item = menu.findItem(R.id.ml_menu_save);
            item.setVisible(true);
            String mrl = ((BaseBrowserFragment)current).mMrl;
            item.setIcon(MediaDatabase.getInstance().networkFavExists(Uri.parse(mrl)) ?
                    R.drawable.ic_menu_bookmark_w :
                    R.drawable.ic_menu_bookmark_outline_w);
        } else
            menu.findItem(R.id.ml_menu_save).setVisible(false);
        if (current instanceof MRLPanelFragment)
            menu.findItem(R.id.ml_menu_clean).setVisible(!((MRLPanelFragment) current).isEmpty());
        boolean showLast = current instanceof AudioBrowserFragment || (current instanceof VideoGridFragment && mSettings.getString(PreferencesActivity.VIDEO_LAST, null) != null);
        menu.findItem(R.id.ml_menu_last_playlist).setVisible(showLast);
        return true;
    }

    /**
     * Handle onClick form menu buttons
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        // Current fragment loaded
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder);

        // Handle item selection
        switch (item.getItemId()) {
            case R.id.ml_menu_sortby_name:
            case R.id.ml_menu_sortby_length:
            case R.id.ml_menu_sortby_date:
                if (current == null)
                    break;
                if (current instanceof ISortable) {
                    int sortBy = VideoListAdapter.SORT_BY_TITLE;
                    if (item.getItemId() == R.id.ml_menu_sortby_length)
                        sortBy = VideoListAdapter.SORT_BY_LENGTH;
                    else if(item.getItemId() == R.id.ml_menu_sortby_date)
                        sortBy = VideoListAdapter.SORT_BY_DATE;
                    ((ISortable) current).sortBy(sortBy);
                    supportInvalidateOptionsMenu();
                }
                break;
            case R.id.ml_menu_equalizer:
                showSecondaryFragment(SecondaryActivity.EQUALIZER);
                break;
            // Refresh
            case R.id.ml_menu_refresh:
                forceRefresh(current);
                break;
            // Restore last playlist
            case R.id.ml_menu_last_playlist:
                boolean audio = current instanceof AudioBrowserFragment;
                    Intent i = new Intent(audio ? PlaybackService.ACTION_REMOTE_LAST_PLAYLIST :
                           PlaybackService.ACTION_REMOTE_LAST_VIDEO_PLAYLIST);
                    sendBroadcast(i);
                break;
            case android.R.id.home:
                // Slide down the audio player.
                if (slideDownAudioPlayer())
                    break;
                /* Toggle the sidebar */
                if (mDrawerToggle.onOptionsItemSelected(item)) {
                    return true;
                }
                break;
            case R.id.ml_menu_clean:
                if (current instanceof MRLPanelFragment)
                    ((MRLPanelFragment)current).clearHistory();
                break;
            case R.id.ml_menu_save:
                if (current == null)
                    break;
                ((NetworkBrowserFragment)current).toggleFavorite();
                item.setIcon(R.drawable.ic_menu_bookmark_w);
                break;
        }
        mDrawerLayout.closeDrawer(mNavigationView);
        return super.onOptionsItemSelected(item);
    }

    private void forceRefresh(Fragment current) {
        if (!mMediaLibrary.isWorking()) {
            if(current != null && current instanceof IRefreshable)
                ((IRefreshable) current).refresh();
            else
                mMediaLibrary.scanMediaItems(true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ACTIVITY_RESULT_PREFERENCES) {
            if (resultCode == PreferencesActivity.RESULT_RESCAN)
                mMediaLibrary.scanMediaItems(true);
            else if (resultCode == PreferencesActivity.RESULT_RESTART) {
                final Intent intent = getIntent();
                if (intent != null && intent.getAction() != null) {
                    finish();
                    startActivity(intent);
                }
            }
        } else if (requestCode == ACTIVITY_RESULT_OPEN && resultCode == RESULT_OK){
            MediaUtils.openUri(this, data.getData());
        }
    }

    public void setMenuFocusDown(boolean idIsEmpty, int id) {
        if (mMenu == null)
            return;
        //Save menu items ids for focus control
        final int[] menu_controls = new int[mMenu.size()+1];
        for (int i = 0 ; i < mMenu.size() ; i++){
            menu_controls[i] = mMenu.getItem(i).getItemId();
        }
        menu_controls[mMenu.size()] = mActionBarIconId;
        /*menu_controls = new int[]{R.id.ml_menu_search,
            R.id.ml_menu_open_mrl, R.id.ml_menu_sortby,
            R.id.ml_menu_last_playlist, R.id.ml_menu_refresh,
            mActionBarIconId};*/
        int pane = mSlidingPane.getState();
        for(int r : menu_controls) {
            View v = findViewById(r);
            if (v != null) {
                if (!idIsEmpty)
                    v.setNextFocusDownId(id);
                else {
                    if (pane ==  mSlidingPane.STATE_CLOSED) {
                        v.setNextFocusDownId(R.id.play_pause);
                    } else if (pane == mSlidingPane.STATE_OPENED) {
                        v.setNextFocusDownId(R.id.header_play_pause);
                    } else if (pane ==
                            mSlidingPane.STATE_OPENED_ENTIRELY) {
                        v.setNextFocusDownId(r);
                    }
                }
            }
        }
    }

    public void setSearchAsFocusDown(boolean idIsEmpty, View parentView, int id) {
        View playPause = findViewById(R.id.header_play_pause);

        if (!idIsEmpty) {
            View list;
            int pane = mSlidingPane.getState();

            if (parentView == null)
                list = findViewById(id);
            else
                list = parentView.findViewById(id);

            if (list != null) {
                if (pane == mSlidingPane.STATE_OPENED_ENTIRELY) {
                    list.setNextFocusDownId(id);
                } else if (pane == mSlidingPane.STATE_OPENED) {
                    list.setNextFocusDownId(R.id.header_play_pause);
                    playPause.setNextFocusUpId(id);
                }
            }
        } else {
            playPause.setNextFocusUpId(R.id.ml_menu_search);
        }
    }

    // Note. onKeyDown will not occur while moving within a list
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Filter for LG devices, see https://code.google.com/p/android/issues/detail?id=78154
        if ((keyCode == KeyEvent.KEYCODE_MENU) &&
                (Build.VERSION.SDK_INT <= 16) &&
                (Build.MANUFACTURER.compareTo("LGE") == 0)) {
            return true;
        }
        if (mFocusedPrior == 0)
            setMenuFocusDown(true, 0);
        if (getCurrentFocus() != null)
            mFocusedPrior = getCurrentFocus().getId();
        return super.onKeyDown(keyCode, event);
    }

    // Note. onKeyDown will not occur while moving within a list
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        //Filter for LG devices, see https://code.google.com/p/android/issues/detail?id=78154
        if ((keyCode == KeyEvent.KEYCODE_MENU) &&
                (Build.VERSION.SDK_INT <= 16) &&
                (Build.MANUFACTURER.compareTo("LGE") == 0)) {
            openOptionsMenu();
            return true;
        }
        View v = getCurrentFocus();
        if (v == null)
            return super.onKeyUp(keyCode, event);
        if ((mActionBarIconId == -1) &&
                (v.getId() == -1)  &&
                (v.getNextFocusDownId() == -1) &&
                (v.getNextFocusUpId() == -1) &&
                (v.getNextFocusLeftId() == -1) &&
                (v.getNextFocusRightId() == -1)) {
            mActionBarIconId = UiTools.generateViewId();
            v.setId(mActionBarIconId);
            v.setNextFocusUpId(mActionBarIconId);
            v.setNextFocusDownId(mActionBarIconId);
            v.setNextFocusLeftId(mActionBarIconId);
            v.setNextFocusRightId(R.id.ml_menu_search);
            if (AndroidUtil.isHoneycombOrLater())
                v.setNextFocusForwardId(mActionBarIconId);
            if (findViewById(R.id.ml_menu_search) != null)
                findViewById(R.id.ml_menu_search).setNextFocusLeftId(mActionBarIconId);
        }
        return super.onKeyUp(keyCode, event);
    }

    private void reloadPreferences() {
        mCurrentFragment = mSettings.getInt("fragment_id", R.id.nav_video);
    }

    @Override
    public Cursor runQuery(CharSequence constraint) {
        return MediaDatabase.getInstance().queryMedia(constraint.toString());
    }

    private static class MainActivityHandler extends WeakHandler<MainActivity> {
        public MainActivityHandler(MainActivity owner) {
            super(owner);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity ma = getOwner();
            if(ma == null) return;

            switch (msg.what) {
                case ACTIVITY_SHOW_INFOLAYOUT:
                    ma.mInfoLayout.setVisibility(View.VISIBLE);
                    break;
                case ACTIVITY_SHOW_PROGRESSBAR:
                    ma.setSupportProgressBarIndeterminateVisibility(true);
                    ma.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                case ACTIVITY_HIDE_PROGRESSBAR:
                    ma.setSupportProgressBarIndeterminateVisibility(false);
                    ma.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                case ACTIVITY_SHOW_TEXTINFO:
                    String info = (String) msg.obj;
                    int max = msg.arg1;
                    int progress = msg.arg2;
                    ma.mInfoText.setText(info);
                    ma.mInfoProgress.setMax(max);
                    ma.mInfoProgress.setProgress(progress);

                    if (info == null) {
                    /* Cancel any upcoming visibility change */
                        removeMessages(ACTIVITY_SHOW_INFOLAYOUT);
                        ma.mInfoLayout.setVisibility(View.GONE);
                    }
                    else {
                    /* Slightly delay the appearance of the progress bar to avoid unnecessary flickering */
                        if (!hasMessages(ACTIVITY_SHOW_INFOLAYOUT)) {
                            Message m = new Message();
                            m.what = ACTIVITY_SHOW_INFOLAYOUT;
                            sendMessageDelayed(m, 300);
                        }
                    }
                    break;
            }
        }
    }

    public void hideKeyboard(){
        ((InputMethodManager) VLCApplication.getAppContext().getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
                getWindow().getDecorView().getRootView().getWindowToken(), 0);
    }

    public void showProgressBar() {
        mHandler.obtainMessage(ACTIVITY_SHOW_PROGRESSBAR).sendToTarget();
    }

    public void hideProgressBar() {
        mHandler.obtainMessage(ACTIVITY_HIDE_PROGRESSBAR).sendToTarget();
    }

    public void sendTextInfo(String info, int progress, int max) {
        mHandler.obtainMessage(ACTIVITY_SHOW_TEXTINFO, max, progress, info).sendToTarget();
    }

    public void clearTextInfo() {
        mHandler.obtainMessage(ACTIVITY_SHOW_TEXTINFO, 0, 100, null).sendToTarget();
    }

    protected void onPanelClosedUiSet() {
        mDrawerLayout.setDrawerLockMode(HackyDrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    protected void onPanelOpenedEntirelyUiSet() {
        mDrawerLayout.setDrawerLockMode(HackyDrawerLayout.LOCK_MODE_UNLOCKED);
    }

    protected void onPanelOpenedUiSet() {
        mDrawerLayout.setDrawerLockMode(HackyDrawerLayout.LOCK_MODE_UNLOCKED);
        removeTipViewIfDisplayed();
    }


    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_placeholder);

        if(current == null || (item != null && mCurrentFragment == id)) { /* Already selected */
            if (mFocusedPrior != 0)
                requestFocusOnSearch();
            mDrawerLayout.closeDrawer(mNavigationView);
            return false;
        }

        // This should not happen
        if(item == null)
            return false;

        String tag = getTag(id);
        switch (id){
            case R.id.nav_about:
                showSecondaryFragment(SecondaryActivity.ABOUT);
                break;
            case R.id.nav_settings:
                startActivityForResult(new Intent(this, PreferencesActivity.class), ACTIVITY_RESULT_PREFERENCES);
                break;
            case R.id.nav_directories:
                if (TextUtils.equals(BuildConfig.FLAVOR_target, "chrome")) {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("audio/* video/*");
                    startActivityForResult(intent, ACTIVITY_RESULT_OPEN);
                    mDrawerLayout.closeDrawer(mNavigationView);
                    return true;
                }
            default:
                /* Slide down the audio player */
                slideDownAudioPlayer();

                /* Switch the fragment */
                Fragment fragment = getFragment(id);
                if (fragment instanceof MediaBrowserFragment)
                    ((MediaBrowserFragment)fragment).setReadyToDisplay(false);
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                ft.replace(R.id.fragment_placeholder, fragment, tag);
                ft.addToBackStack(getTag(mCurrentFragment));
                ft.commit();
                mCurrentFragment = id;

                if (mFocusedPrior != 0)
                    requestFocusOnSearch();
        }
        mDrawerLayout.closeDrawer(mNavigationView);
        mNavigationView.setCheckedItem(mCurrentFragment);
        return true;
    }

    private String getTag(int id){
        switch (id){
            case R.id.nav_about:
                return ID_ABOUT;
            case R.id.nav_settings:
                return ID_PREFERENCES;
            case R.id.nav_audio:
                return ID_AUDIO;
            case R.id.nav_directories:
                return ID_DIRECTORIES;
            case R.id.nav_history:
                return ID_HISTORY;
            case R.id.nav_mrl:
                return ID_MRL;
            case R.id.nav_network:
                return ID_NETWORK;
            default:
                return ID_VIDEO;
        }
    }

    private void requestFocusOnSearch() {
        View search = findViewById(R.id.ml_menu_search);
        if (search != null)
            search.requestFocus();
    }
}
