
package com.atakmap.android.hierarchy;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.features.FeatureSetHierarchyListItem;
import com.atakmap.android.hashtags.util.HashtagUtils;
import com.atakmap.android.hashtags.HashtagSearch;
import com.atakmap.android.hashtags.HashtagContent;
import com.atakmap.android.hierarchy.HierarchyListUserSelect.ButtonMode;
import com.atakmap.android.hierarchy.action.Action;
import com.atakmap.android.hierarchy.action.Actions;
import com.atakmap.android.hierarchy.action.GoTo;
import com.atakmap.android.hierarchy.action.Search;
import com.atakmap.android.hierarchy.action.Visibility;
import com.atakmap.android.hierarchy.action.Visibility2;
import com.atakmap.android.hierarchy.filters.EmptyListFilter;
import com.atakmap.android.hierarchy.filters.FOVFilter;
import com.atakmap.android.hierarchy.filters.MultiFilter;
import com.atakmap.android.hierarchy.HierarchyListItem.ComparatorSort;
import com.atakmap.android.hierarchy.HierarchyListItem.Sort;
import com.atakmap.android.hierarchy.HierarchyListItem.SortAlphabet;
import com.atakmap.android.hierarchy.HierarchyListItem.SortDistanceFrom;
import com.atakmap.android.hierarchy.items.AbstractHierarchyListItem;
import com.atakmap.android.hierarchy.items.AbstractHierarchyListItem2;
import com.atakmap.android.hierarchy.items.MapItemHierarchyListItem;
import com.atakmap.android.hierarchy.items.MapItemUser;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.ILocation;
import com.atakmap.android.maps.Location;
import com.atakmap.android.maps.LocationSearchManager;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.overlay.MapOverlay2;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.util.AltitudeUtilities;
import com.atakmap.android.util.LimitingThread;
import com.atakmap.annotations.DeprecatedApi;
import com.atakmap.app.R;
import com.atakmap.coremap.conversions.Angle;
import com.atakmap.coremap.conversions.AngleUtilities;
import com.atakmap.coremap.conversions.CoordinateFormat;
import com.atakmap.coremap.conversions.CoordinateFormatUtilities;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.conversion.EGM96;

import com.atakmap.coremap.maps.coords.DistanceCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.coremap.maps.coords.NorthReference;
import com.atakmap.map.AtakMapView;

import com.atakmap.coremap.locale.LocaleUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;

/**
 * This is the main list adapter used by Overlay Manager (OM)
 * List items are pulled from map overlays registered to
 * {@link MapView#getMapOverlayManager()}
 */
public class HierarchyListAdapter extends BaseAdapter implements
        AtakMapView.OnMapMovedListener,
        PointMapItem.OnPointChangedListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private final static String TAG = "HierarchyListAdapter";

    // 3-state visibility
    public static final int UNCHECKED = 0;
    public static final int CHECKED = 1;
    public static final int SEMI_CHECKED = 2;

    // Max list refresh interval in milliseconds (1 second)
    private static final int REFRESH_INTERVAL = 1000;

    // Max UI refresh interval in milliseconds (~1/30 second)
    // There's no reason to refresh faster than the UI frame rate (usually 30 fps)
    private static final int UI_REFRESH_INTERVAL = 33;

    // Map view, context, and preferences
    private final MapView mapView;
    private final Context context;
    private final SharedPreferences prefs;

    // Device UID and callsign
    private final String devUID;
    private final String devCallsign;

    // The OM intent and drop-down receiver
    // This handles OM-related intents and any UI outside of the list view
    private final HierarchyListReceiver receiver;

    // The screen size configuration (small or large)
    // This determines which layout is used by list items
    private final int screenSize;

    // The view layout used to display OM
    private HierarchyManagerView view;

    // Whether OM is busy starting up
    private boolean initiating = false;

    // Whether OM is active (drop-down opened) or not
    private boolean active = false;

    // Whether outside refresh requests are being blocked
    private boolean blockRefresh = false;

    // The top-level Overlay Manager list
    private ListModelImpl model;

    // The current list being displayed
    private HierarchyListItem currentList;

    // The current list of items being displayed
    private final List<HierarchyListItem> items = new ArrayList<>();

    // List of pending items to update once busy touch events are finished
    private final List<HierarchyListItem> pendingItems = new ArrayList<>();

    // The back-stack of lists
    private Stack<HierarchyListItem> prevListStack = new Stack<>();

    // The back-stack of scroll positions so the user doesn't lose their place
    // after navigating into a hierarchy of lists
    private Stack<Integer> prevListScroll = new Stack<>();

    // Support for 'user selected' item handlers
    // i.e. Multi-select export or deletion
    private HierarchyListUserSelect userSelectHandler;

    // The list of selected item UIDs
    private final List<String> selectedPaths = new ArrayList<>();

    // The button used to finish a multi-select action
    private Button processBtn;

    // Whether to prevent the user from backing out of multi-select mode
    // This is set to 'true' when OM is started from a multi-select intent
    private boolean selectModeOnly = false;

    // The current item/list filter - applied while performing a refresh
    private MultiFilter currFilter;

    // The current sort method (alphabetic by default)
    private Class<?> curSort = SortAlphabet.class;

    // Whether to perform filtering after a refresh is finished
    private boolean postFilter = true;

    // Default post-refresh filter which hides empty lists
    private final EmptyListFilter emptyListFilter = new EmptyListFilter();

    // Search parameters
    private SearchResults searchResults;
    private String searchTerms;
    private boolean showSearchLoader = false;

    // Inverse of whether the "Show All" checkbox is activated
    // i.e. "Show All" checked -> filterFOV = false
    private boolean filterFOV;

    // The currently highlighted item UID
    private String highlightUID;

    // Preference values
    private int rangeSystem;
    private Angle bearingUnits;
    private NorthReference northRef;
    private CoordinateFormat coordFmt;
    private boolean showingLocationItem;

    /**
     * Create a new Overlay Manager list adapter
     * @param context The application context
     * @param mapView ATAK map view
     * @param selectHandler Multi-select action (null by default)
     * @param hlr The OM intent receiver
     */
    public HierarchyListAdapter(Context context, MapView mapView,
            HierarchyListUserSelect selectHandler, HierarchyListReceiver hlr) {
        this.context = context;
        this.mapView = mapView;
        this.receiver = hlr;
        this.devUID = mapView.getSelfMarker().getUID();
        this.devCallsign = mapView.getDeviceCallsign();
        this.screenSize = context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Read initial preference values
        this.filterFOV = this.prefs.getBoolean(FOVFilter.PREF, false);
        this.rangeSystem = Integer.parseInt(prefs.getString(
                "rab_rng_units_pref", String.valueOf(1)));
        this.bearingUnits = Angle.findFromValue(Integer.parseInt(
                prefs.getString("rab_brg_units_pref", String.valueOf(0))));
        this.northRef = NorthReference.findFromValue(Integer.parseInt(
                prefs.getString("rab_north_ref_pref", String.valueOf(
                        NorthReference.MAGNETIC.getValue()))));
        this.coordFmt = CoordinateFormat.find(prefs.getString(
                "coord_display_pref", context.getString(
                        R.string.coord_display_pref_default)));

        // Ensure our self marker doesn't show up in OM
        Marker self = ATAKUtilities.findSelf(mapView);
        if (self != null)
            self.setMetaBoolean("addToObjList", false);

        this.userSelectHandler = selectHandler;
        if (selectHandler != null) {
            // Select handler was initiated from intent - hide back out button
            this.selectModeOnly = true;
        }

        // Setup the default filter
        resetFilter();

        // Set an empty root list - this will be filled once the initial refresh
        // is finished
        setModel(new ListModelImpl(this, this.rootSearchEngine,
                this.currFilter));
    }

    /**
     * To be called when the drop-down is opened
     */
    void registerListener() {
        if (!this.active) {
            this.active = true;
            // Refresh on map moved (FOV filter only)
            this.mapView.addOnMapMovedListener(this);
            Marker self = this.mapView.getSelfMarker();
            if (self != null)
                self.addOnPointChangedListener(this);
            this.prefs.registerOnSharedPreferenceChangeListener(this);
            this.initiating = true;
            refreshList();
        }
    }

    /**
     * To be called when the drop-down is closed
     */
    void unregisterListener() {
        if (this.active) {
            this.mapView.removeOnMapMovedListener(this);
            Marker self = this.mapView.getSelfMarker();
            if (self != null)
                self.removeOnPointChangedListener(this);
            this.prefs.unregisterOnSharedPreferenceChangeListener(this);
            this.active = false;
            this.initiating = false;
            this.filterFOV = false;
            this.refreshThread.dispose(false);
            this.uiRefreshThread.dispose(false);
            this.searchThread.dispose(false);
            this.prevListStack.clear();
            HierarchyListItem curList = this.currentList;
            this.currentList = this.model;
            dispose(curList);
            if (curList != this.model)
                dispose(this.model);
        }
    }

    /**
     * Check whether the Overlay Manager adapter instance is active
     * If inactive then it should not be used
     *
     * @return True if active
     */
    public boolean isActive() {
        return this.active;
    }

    // Thread which handles a refresh of the current list (at most once per second)
    private final LimitingThread refreshThread = new LimitingThread(
            "OM-Refresh", new Runnable() {
                @Override
                public void run() {
                    if (active) {
                        refreshListImpl();
                        try {
                            Thread.sleep(REFRESH_INTERVAL);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            });

    // Thread which handles how often the UI is updated
    private final LimitingThread uiRefreshThread = new LimitingThread("OM-UI",
            new Runnable() {
                @Override
                public void run() {
                    if (active) {
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                if (active) {
                                    receiver.setViewToMode();
                                    superNotifyDataSetChanged();
                                }
                            }
                        });
                        try {
                            Thread.sleep(UI_REFRESH_INTERVAL);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            });

    // Thread which handles any search requests
    private final LimitingThread searchThread = new LimitingThread("OM-Search",
            new Runnable() {
                @Override
                public void run() {
                    if (active)
                        searchImpl();
                }
            });

    // Handles search requests made on the top-level OM list
    private final Search rootSearchEngine = new Search() {
        @Override
        public Set<HierarchyListItem> find(String term) {
            // XXX - Is this even used?
            SortedSet<Location> results = LocationSearchManager.find(term);

            //tree set uses the comparator to determine equality/duplicates
            SortedSet<HierarchyListItem> retval = new TreeSet<>(
                    MENU_ITEM_COMP);

            final MapGroup rootGroup = mapView.getRootGroup();

            Map<String, String> meta = new HashMap<>();

            MapItem impl;
            HierarchyListItem item;
            for (Location hit : results) {
                item = null;
                if (hit.getUID() != null) {
                    meta.put("uid", hit.getUID());
                    impl = rootGroup.deepFindItem(meta);

                    if (impl != null)
                        item = new MapItemHierarchyListItem(mapView, impl);
                }

                if (item == null) {
                    Log.w(TAG, "Failed to find MapItem for search hit "
                            + hit.getFriendlyName()
                            + " (" + hit.getUID() + ")");
                    item = new LocationHierarchyListItem(hit);
                }

                retval.add(item);
            }

            if (model == null)
                return retval;

            // Search root list
            List<Search> items = model.getChildActions(Search.class);
            for (Search s : items) {
                if (!(s instanceof HierarchyListItem))
                    continue;

                item = (HierarchyListItem) s;
                String title = item.getTitle();

                // Search overlay descendants
                try {
                    retval.addAll(s.find(term));
                } catch (Exception e) {
                    Log.e(TAG, "Search threw exception on " + title, e);
                }

                // Search overlay title
                if (!FileSystemUtils.isEmpty(title)
                        && title.toLowerCase(LocaleUtil.getCurrent())
                                .contains(term))
                    retval.add(item);

                // Search overlay description, if any
                if (item instanceof HierarchyListItem2) {
                    String desc = ((HierarchyListItem2) item).getDescription();
                    if (!FileSystemUtils.isEmpty(desc)
                            && desc.toLowerCase(LocaleUtil.getCurrent())
                                    .contains(term))
                        retval.add(item);
                }
            }
            return retval;
        }
    };

    /**
     * Perform a search on the current list
     *
     * @param terms Search terms
     */
    public void searchListAndFilterResults(String terms) {
        boolean showLoader = this.showSearchLoader || this.searchTerms == null
                || !this.searchTerms.equals(terms);
        this.searchTerms = terms;
        if (terms == null || terms.trim().isEmpty()) {
            if (this.currentList == this.searchResults) {
                // show the underlying list when the search textfield is cleared
                popList();
            }
            this.receiver.showSearchLoader(false);
            return;
        }

        if (this.currentList != this.searchResults) {
            SearchResults old = this.searchResults;
            this.searchResults = new SearchResults(this, this.currFilter);

            // Leave old results on screen until new results are returned
            // Prevents flickering effect when changing search terms or refreshing
            if (old != null)
                this.searchResults.setResults(old);
            pushList(this.searchResults, false);
        }
        if (showLoader)
            this.receiver.showSearchLoader(true);
        this.showSearchLoader = false;
        this.searchThread.exec();
    }

    private synchronized void searchImpl() {
        //beginTimeMeasure("search.find(" + terms + ")");
        final HierarchyListItem curList = getCurrentList(true);
        final String terms = this.searchTerms != null ? this.searchTerms : "";

        Map<String, HierarchyListItem> uidMap = new HashMap<>();

        try {
            // Perform recursive search
            List<HierarchyListItem> findResults = (curList instanceof Search)
                    ? new ArrayList<>(((Search) curList).find(terms))
                    : new ArrayList<HierarchyListItem>();

            // Limit 1 result per UID
            for (HierarchyListItem item : findResults)
                uidMap.put(item.getUID(), item);

            // Search by tags, if any
            final List<String> tags = HashtagUtils
                    .extractTags(this.searchTerms);
            if (!tags.isEmpty() && curList instanceof HashtagSearch) {
                Collection<HashtagContent> contents = ((HashtagSearch) curList)
                        .search(tags);
                for (HashtagContent c : contents) {
                    if (c instanceof HierarchyListItem) {
                        HierarchyListItem item = (HierarchyListItem) c;
                        uidMap.put(item.getUID(), item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to complete search terms: " + terms, e);
        }

        final List<HierarchyListItem> results = new ArrayList<>(
                uidMap.values());

        // Sort results using current list's sort method
        Sort sort = HierarchyListReceiver.findSort(
                curList, getSortType());
        Comparator<HierarchyListItem> comp = MENU_ITEM_COMP;
        if (sort instanceof ComparatorSort)
            comp = ((ComparatorSort) sort).getComparator();
        else if (sort instanceof SortDistanceFrom)
            comp = new ItemDistanceComparator((SortDistanceFrom) sort);
        Collections.sort(results, comp);

        //endTimeMeasure("search.find(" + terms + ")");

        this.mapView.post(new Runnable() {
            @Override
            public void run() {
                if (receiver != null)
                    receiver.showSearchLoader(false);
                if (searchResults != null) {
                    searchResults.setResults(results);
                    notifyDataSetChanged();
                }
            }
        });
    }

    /**
     * Set the sort of the current view
     *
     * @param sortType Sort class
     */
    public void sort(Class<?> sortType) {
        if (sortType == null)
            sortType = SortAlphabet.class;
        this.curSort = sortType;
        refreshList();
    }

    public Class<?> getSortType() {
        return this.curSort;
    }

    /**
     * Filter out items that are not in the map bounds
     *
     * @param filterOn True to turn filter on
     */
    public void filterFOV(boolean filterOn) {
        if (this.filterFOV != filterOn) {
            this.filterFOV = filterOn;
            this.prefs.edit().putBoolean(FOVFilter.PREF, filterOn).apply();
            if (this.searchTerms != null)
                this.showSearchLoader = true;
            refreshList();
        }
    }

    @Override
    public void notifyDataSetChanged() {
        ((Activity) this.mapView.getContext()).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                model.onChildRefresh(false);
                updateItems();
            }
        });
        this.uiRefreshThread.exec();
    }

    /**
     * Individual item has finished updating
     *
     * @param item List item
     */
    public void notifyDataSetChanged(final HierarchyListItem item,
            final boolean sizeChanged) {
        ((Activity) this.mapView.getContext()).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Notify adapter (immediately if necessary)
                if (currentList == item)
                    updateItems();
                if (sizeChanged)
                    model.onChildRefresh(true);

                // Retry menu navigation
                if (navInProgress && navPath != null && (navPath.contains(
                        item.getUID())
                        || SystemClock.elapsedRealtime()
                                - navTime > REFRESH_INTERVAL))
                    navigateTo(navPath, false);

                // Refresh search
                if (searchTerms != null)
                    searchListAndFilterResults(searchTerms);
            }
        });
        this.uiRefreshThread.exec();
    }

    public void notifyDataSetChanged(final HierarchyListItem item) {
        notifyDataSetChanged(item, true);
    }

    private void superNotifyDataSetChanged() {
        if (receiver.isTouchActive())
            this.uiRefreshThread.exec();
        else {
            // Pending list update while touch was busy
            if (!pendingItems.isEmpty()) {
                items.clear();
                items.addAll(pendingItems);
                pendingItems.clear();
            }
            super.notifyDataSetChanged();
        }
    }

    /**
     * Toggle the post filter (culls empty lists)
     */
    private void setPostFilter(boolean on) {
        if (this.postFilter != on) {
            this.postFilter = on;
            updateItems();
        }
    }

    /**
     * Apply the post filter to the current list
     */
    private void applyPostFilter() {
        if (this.currentList instanceof AbstractHierarchyListItem2)
            ((AbstractHierarchyListItem2) this.currentList)
                    .setPostFilter(postFilter ? emptyListFilter : null);
    }

    /**
     * Update the items displayed in the current list
     * This is the only place where this.items should be modified
     */
    private void updateItems() {
        ((Activity) this.mapView.getContext()).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                List<HierarchyListItem> newItems = new ArrayList<>();
                if (currentList != null) {
                    applyPostFilter();
                    newItems.addAll(getChildren(currentList));
                }
                showingLocationItem = false;
                for (HierarchyListItem item : newItems) {
                    if (item instanceof ILocation || item instanceof Location) {
                        showingLocationItem = true;
                        break;
                    }
                }

                // Not synchronized since these lists are only touched on UI
                pendingItems.clear();
                if (receiver.isTouchActive()) {
                    // Add these items to the pending list until the touch
                    // is finished
                    pendingItems.addAll(newItems);
                } else {
                    items.clear();
                    items.addAll(newItems);
                }
                superNotifyDataSetChanged();
            }
        });
    }

    /**
     * Return the number of list items to be displayed
     * @return Number of list items
     */
    @Override
    public int getCount() {
        return this.items.size();
    }

    /**
     * Get an item at the given position
     * @param position List item position
     * @return The list item
     */
    @Override
    public Object getItem(int position) {
        if (position < 0 || position >= getCount())
            return null;

        HierarchyListItem item = this.items.get(position);

        // Compatibility with feature sets
        if (item instanceof EmptyListItem
                && (this.currentList instanceof FeatureSetHierarchyListItem
                        || !isGetChildrenSupported(this.currentList)))
            item = this.currentList.getChildAt(position);

        return item;
    }

    /**
     * Unused - ignore
     * @param position List item position
     * @return 0
     */
    @Override
    public long getItemId(int position) {
        return 0;
    }

    /**
     * Generate the view for a given list item
     * @param position List item position
     * @param row View row to update (null = needs to be inflated)
     * @param parent The parent list view
     * @return The newly created/updated row view
     */
    @Override
    public View getView(int position, View row, ViewGroup parent) {
        //Log.d(TAG, "Calling getView #" + getCountNum);
        //beginTimeMeasure("getView(" + position + ")");

        // Get the item at this position in the list
        HierarchyListItem item = (HierarchyListItem) getItem(position);
        HierarchyListItem2 item2 = item instanceof HierarchyListItem2
                ? (HierarchyListItem2) item
                : null;

        // This row uses a custom view determined by its list item class
        View r2;
        if (item2 != null && (r2 = item2.getListItemView(row, parent)) != null)
            return r2;

        // Check if the row view needs to be created
        ViewHolder h = row != null && row.getTag() instanceof ViewHolder
                ? (ViewHolder) row.getTag()
                : null;
        if (h == null) {
            row = LayoutInflater.from(context).inflate(
                    screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE
                            ? R.layout.hierarchy_manager_list_item
                            : R.layout.hierarchy_manager_list_item_sm_med,
                    parent, false);
            h = new ViewHolder();
            h.toggleParent = row.findViewById(
                    R.id.hierarchy_manager_toggle_parent);
            h.toggleParent.setOnClickListener(h);
            h.checkbox = row.findViewById(
                    R.id.hierarchy_manager_list_item_checkbox);
            h.vizButton = row.findViewById(
                    R.id.hierarchy_manager_visibility_iv);
            h.icon = row.findViewById(
                    R.id.hierarchy_manager_list_item_icon);
            h.title = row.findViewById(
                    R.id.hierarchy_manager_list_item_title);
            h.desc = row.findViewById(
                    R.id.hierarchy_manager_list_item_desc);
            h.rabView = row.findViewById(
                    R.id.hierarchy_manager_list_item_bearing_view);
            h.bearing = row.findViewById(
                    R.id.hierarchy_manager_list_item_dir_text_deg);
            h.distance = row.findViewById(
                    R.id.hierarchy_manager_list_item_dir_text);
            h.elevation = row.findViewById(
                    R.id.hierarchy_manager_list_item_el_text);
            h.extraContainer = row.findViewById(
                    R.id.hierarchy_manager_list_item_extra_view_layout);
            row.setTag(h);
        }
        h.item = item;

        // Null child - hide the row
        if (item == null) {
            Log.w(TAG, "Failed to getChildAt(" + position + "), currentList = "
                    + this.currentList);
            row.setVisibility(View.GONE);
            return row;
        }

        // Default view states
        row.setVisibility(View.VISIBLE);
        h.checkbox.setVisibility(View.GONE);
        h.vizButton.setVisibility(View.GONE);

        if (this.userSelectHandler != null) {
            if (this.userSelectHandler.isMultiSelect()) {
                // if there is a custom handler use the checkbox and hide the visibility icons
                int state = getCheckValue(item);
                h.checkbox.setImageResource(state == UNCHECKED
                        ? R.drawable.btn_check_off
                        : (state == SEMI_CHECKED
                                ? R.drawable.btn_check_semi
                                : R.drawable.btn_check_on));
                h.checkbox.setVisibility(View.VISIBLE);
            }
        } else {
            // if there is no custom handler, use the visibility toggles
            final Visibility itemViz = item.getAction(Visibility.class);
            final Visibility2 itemViz2 = item.getAction(Visibility2.class);
            if (itemViz != null || itemViz2 != null) {
                h.vizButton.setVisibility(View.VISIBLE);
                int vizState = itemViz2 != null ? itemViz2.getVisibility()
                        : (itemViz.isVisible() ? Visibility2.VISIBLE
                                : Visibility2.INVISIBLE);
                int vizRes;
                switch (vizState) {
                    default:
                    case Visibility2.INVISIBLE:
                        vizRes = R.drawable.overlay_not_visible;
                        break;
                    case Visibility2.SEMI_VISIBLE:
                        vizRes = R.drawable.overlay_semi_visible;
                        break;
                    case Visibility2.VISIBLE:
                        vizRes = R.drawable.overlay_visible;
                }
                h.vizButton.setImageResource(vizRes);
                h.vizOn = vizState != Visibility2.INVISIBLE;
            }
        }

        // Apply green background color to the highlighted item
        row.setBackgroundColor(this.highlightUID != null
                && this.highlightUID.equals(item.getUID()) ? 0x8000AF4F : 0);

        // Set the title
        String t = item.getTitle();
        if (t != null)
            t = t.trim();
        if (FileSystemUtils.isEmpty(t))
            t = context.getString(R.string.untitled_item);
        else if (t.equals(this.devUID))
            t = this.devCallsign;
        h.title.setText(t);

        h.icon.setVisibility(View.INVISIBLE);
        h.icon.setColorFilter(item.getIconColor(), PorterDuff.Mode.MULTIPLY);

        // Set the icon
        Drawable iconDr;
        String iconUri = item.getIconUri();
        if (item2 != null && (iconDr = item2.getIconDrawable()) != null) {
            h.icon.setImageDrawable(iconDr);
            h.icon.setColorFilter(item.getIconColor(),
                    PorterDuff.Mode.MULTIPLY);
            h.icon.setVisibility(View.VISIBLE);
        } else if (iconUri != null && iconUri.equals("gone"))
            h.icon.setVisibility(View.GONE);
        else
            ATAKUtilities.SetIcon(this.context, h.icon, iconUri,
                    item.getIconColor());

        // Set the description
        h.desc.setText("");
        h.desc.setVisibility(View.GONE);
        h.rabView.setVisibility(View.GONE);
        String desc = item2 != null ? item2.getDescription() : null;

        // Custom description
        if (desc != null) {
            h.desc.setVisibility(View.VISIBLE);
            if (desc.toLowerCase(LocaleUtil.getCurrent()).startsWith("<html>"))
                h.desc.setText(Html.fromHtml(desc));
            else
                h.desc.setText(desc);
        }

        // Item has a location
        else if (adaptLocationItem(item)) {
            // Distance from self marker to item
            double dist = item.getLocalData("dist", Number.class)
                    .doubleValue();
            h.distance.setText(SpanUtilities.formatType(this.rangeSystem, dist,
                    Span.METER));

            // Bearing from self marker to item
            double bearing = item.getLocalData("bearing", Number.class)
                    .doubleValue();
            h.bearing.setText(AngleUtilities.format(bearing, this.bearingUnits)
                    + this.northRef.getAbbrev());

            // Item coordinate and elevation
            double lat = item.getLocalData("latitude", Number.class)
                    .doubleValue();
            double lon = item.getLocalData("longitude", Number.class)
                    .doubleValue();
            double el = item.getLocalData("el", Number.class).doubleValue();

            // XXY is el HAE or MSL - in the original code it was MSL.
            GeoPoint point = new GeoPoint(lat, lon,
                    EGM96.getHAE(lat, lon, el));
            h.elevation.setText(AltitudeUtilities.format(point, this.prefs));
            h.desc.setText(CoordinateFormatUtilities.formatToString(point,
                    this.coordFmt));
            h.desc.setVisibility(View.VISIBLE);
            h.rabView.setVisibility(View.VISIBLE);
        }

        // Show the number of child items by default
        else if (item.isChildSupported()) {
            int childCount = item.getChildCount();
            StringBuilder sb = new StringBuilder();
            if (childCount == 1)
                sb.append("1 Item");
            else
                sb.append(childCount).append(" Items");
            if (childCount > 0) {
                int deepCount = item.getDescendantCount();
                if (deepCount > childCount)
                    sb.append(" (").append(deepCount)
                            .append(" including sub-items)");
            }
            h.desc.setText(sb.toString());
            h.desc.setVisibility(View.VISIBLE);
        }

        // Add extra view (if any) to the end of the row
        View extraView = null;
        View existing = h.extraContainer.getChildAt(0);
        if (item2 != null)
            extraView = item2.getExtraView(existing, h.extraContainer);
        if (extraView == null)
            extraView = item.getExtraView(); // Legacy method
        if (extraView != existing) {
            h.extraContainer.removeAllViews();
            if (extraView != null) {
                ViewParent p = extraView.getParent();
                if (p instanceof ViewGroup && p != h.extraContainer)
                    ((ViewGroup) p).removeView(extraView);
                h.extraContainer.addView(extraView, 0);
            }
        }
        h.extraContainer.setVisibility(extraView != null
                ? View.VISIBLE
                : View.GONE);
        //endTimeMeasure("getView(" + position + ")");
        return row;
    }

    /**
     * Holds the item and view instances for each row to improve performance
     */
    private class ViewHolder implements View.OnClickListener {
        HierarchyListItem item;
        View toggleParent;
        ImageView checkbox;
        ImageView vizButton;
        boolean vizOn;
        ImageView icon;
        TextView title;
        TextView desc;
        LinearLayout rabView;
        TextView bearing;
        TextView distance;
        TextView elevation;
        LinearLayout extraContainer;

        @Override
        public void onClick(View v) {
            if (item == null)
                return;

            if (v == toggleParent) {
                if (checkbox.getVisibility() == View.VISIBLE)
                    v = checkbox;
                else if (vizButton.getVisibility() == View.VISIBLE)
                    v = vizButton;
            }

            // Toggle selection of item
            if (v == checkbox) {
                if (userSelectHandler == null)
                    return;
                boolean checked = getCheckValue(item) == UNCHECKED;
                checkbox.setImageResource(checked ? R.drawable.btn_check_on
                        : R.drawable.btn_check_off);
                setItemChecked(item, checked);
            }

            // Toggle visibility of item
            else if (v == vizButton) {
                Visibility visAction = item.getAction(Visibility.class);
                if (visAction != null) {
                    vizOn = !vizOn;
                    vizButton.setImageResource(vizOn
                            ? R.drawable.overlay_visible
                            : R.drawable.overlay_not_visible);
                    // Set visibility asynchronously
                    setVisibleAsync(visAction, vizOn);
                } else
                    vizButton.setImageResource(R.drawable.overlay_not_visible);
            }
        }
    }

    /**
     * Generate a description for a location-based item
     *
     * @param item List item
     * @return True to show location description, false otherwise
     */
    private boolean adaptLocationItem(HierarchyListItem item) {
        if (!(item instanceof ILocation) && !(item instanceof Location))
            return false;

        final GeoPointMetaData loc;

        if (item instanceof ILocation) {
            GeoPoint gp = GeoPoint.createMutable();
            ((ILocation) item).getPoint(gp);
            loc = GeoPointMetaData.wrap(gp);
        } else
            loc = ((Location) item).getLocation();

        item.setLocalData("latitude", loc.get().getLatitude());
        item.setLocalData("longitude", loc.get().getLongitude());

        GeoPoint cLoc = getCurrentLocation();
        item.setLocalData("bearing", cLoc.bearingTo(loc.get()));
        item.setLocalData("dist", cLoc.distanceTo(loc.get()));
        if (loc.get().isAltitudeValid()) {
            item.setLocalData(
                    "el", EGM96.getMSL(loc.get()));
        } else {
            item.setLocalData("el", GeoPoint.UNKNOWN);
        }

        Boolean showLoc = item.getLocalData("showLocation", Boolean.class);
        return showLoc == null || showLoc;
    }

    /**
     * Get the user's location
     *
     * @return Location point
     */
    public GeoPoint getCurrentLocation() {
        Marker self = ATAKUtilities.findSelf(mapView);
        return self != null ? self.getPoint() : mapView.getPoint().get();
    }

    /**
     * Set visibility of a list item asynchronously
     *
     * @param visAction Visibility action
     * @param viz Visibility state (on or off)
     */
    public void setVisibleAsync(final Visibility visAction, final boolean viz) {
        AbstractHierarchyListItem2.async(new Runnable() {
            @Override
            public void run() {
                visAction.setVisible(viz);
                notifyDataSetChanged();
            }
        });
    }

    /**
     * Set the main layout view used by Overlay Manager
     *
     * @param hierarchyManagerView Overlay Manager layout view
     */
    public void setHierManView(HierarchyManagerView hierarchyManagerView) {
        this.view = hierarchyManagerView;

        // TODO push this into interface
        this.selectedPaths.clear();
        this.processBtn = this.view.findViewById(
                R.id.hierarchy_process_user_selected_button);
        if (this.processBtn != null) {
            this.processBtn.setVisibility(this.userSelectHandler == null
                    || !this.userSelectHandler.isMultiSelect()
                    || this.userSelectHandler
                            .getButtonMode() == ButtonMode.VISIBLE_WHEN_SELECTED
                                    ? View.GONE
                                    : View.VISIBLE);
            if (userSelectHandler != null)
                this.processBtn.setText(userSelectHandler.getButtonText());
        }

        // Back out to regular mode
        ImageButton backOut = this.view
                .findViewById(R.id.hierarchy_back_out_mode);
        if (backOut != null)
            backOut.setVisibility(this.userSelectHandler == null
                    || this.selectModeOnly ? View.GONE : View.VISIBLE);

        updateCheckAll();
    }

    private long navTime = -1;
    private List<String> navPath = null;
    private int navStartIndex = 0;
    private boolean navInProgress = false;

    /**
     * Navigate to a page based on a list of page UIDs
     *
     * @param path Page UIDs (excluding root page)
     * @param firstAttempt True if this is the first attempt
     */
    public void navigateTo(final List<String> path, boolean firstAttempt) {
        if (path == null)
            return;
        // In case overlay manager is still loading
        this.navInProgress = true;

        Log.d(TAG, "Attempting to navigate to " + path.toString()
                + ", start = " + this.navStartIndex);

        // Cancel search if active
        endSearch();

        // Make sure we're on root page on the first attempt
        if (firstAttempt && this.currentList != this.model) {
            this.prevListStack.clear();
            setCurrentList(this.model);
            this.navStartIndex = 0;
        }

        setPostFilter(false);

        StringBuilder fullPath = new StringBuilder();
        boolean success = true;
        int lastAttemptIndex = 0;
        outer: for (int p = 0; p < path.size(); p++) {
            String dir = path.get(p);
            if (FileSystemUtils.isEmpty(dir))
                continue;
            fullPath.append("/");
            fullPath.append(dir);
            if (p < this.navStartIndex)
                continue;
            boolean found = false;
            for (int i = 0; i < getCount(); i++) {
                HierarchyListItem item = (HierarchyListItem) getItem(i);
                if (item != null) {
                    String uid = item.getUID();
                    if (uid != null && uid.equals(dir)) {
                        pushList(item, false);
                        HierarchyListItem curList = getCurrentList(true);
                        if (curList != item) {
                            // Push blocked for one reason or another
                            // Just abort navigation here
                            break outer;
                        }
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                lastAttemptIndex = p;
                success = false;
                break;
            }
        }
        this.receiver.refreshTitle();
        if (success) {
            this.navPath = null;
            this.navTime = -1;
            this.navInProgress = false;
            this.navStartIndex = 0;
            setPostFilter(true);
            refreshList();
        } else {
            long curTime = SystemClock.elapsedRealtime();
            if (this.navPath == null || !FileSystemUtils.isEquals(
                    this.navPath, path)) {
                // Save so we can retry after a couple refreshes
                this.navPath = path;
                this.navTime = curTime;
                refreshList();
            }
            this.navStartIndex = lastAttemptIndex;
            if (curTime - this.navTime > REFRESH_INTERVAL) {
                Log.w(TAG, "Failed to navigate to " + fullPath);
                /*Toast.makeText(mapView.getContext(), "Failed to navigate to "
                        + fullPath, Toast.LENGTH_LONG).show();*/
                this.navTime = -1;
                this.navStartIndex = 0;
                this.navPath = null;
                this.navInProgress = false;
                setPostFilter(true);
            }
        }
    }

    public boolean isNavigating() {
        return this.navInProgress;
    }

    /**
     * Clear feature set cursor window
     * @param item Feature set hierarchy list item
     */
    private void dispose(HierarchyListItem item) {
        if (item instanceof HierarchyListItem2)
            ((HierarchyListItem2) item).dispose();
    }

    private void setCurrentList(HierarchyListItem curList) {
        if (this.currentList != curList) {
            HierarchyListItem oldList = this.currentList;
            this.currentList = curList;
            notifyDataSetChanged();

            // Notify OM listeners that the current list has changed
            if (this.receiver != null) {
                // Ignore events while initializing OM
                if (curList instanceof ListModelImpl && (oldList == null
                        || oldList instanceof ListModelImpl))
                    return;
                this.receiver.onCurrentListChanged(this, oldList, curList);
            }
        }
    }

    private void setModel(ListModelImpl model) {
        if (this.model != model) {
            dispose(this.model);
            this.model = model;
        }
        setCurrentList(this.model);
    }

    public void refreshList() {
        if (!blockingRefresh())
            this.refreshThread.exec();
    }

    public synchronized void blockRefresh(boolean block) {
        if (this.blockRefresh != block) {
            /*if (!block) {
                Log.w(TAG, "Unblocking refresh", new Throwable());
            } else {
                Log.d(TAG, "Blocking refresh", new Throwable());
            }*/
            this.blockRefresh = block;
        }
    }

    public synchronized boolean blockingRefresh() {
        return this.blockRefresh;
    }

    /**
     * Call refresh on the current list
     * This method will build the root list if needed
     */
    private void refreshListImpl() {
        if (!this.active || blockingRefresh())
            return;
        if (this.searchTerms != null && !this.searchTerms.isEmpty()) {
            this.showSearchLoader = true;
            this.mapView.post(new Runnable() {
                @Override
                public void run() {
                    receiver.showSearchLoader(true);
                }
            });
        }

        // Filter, sort, etc.
        resetFilter();

        // If we need to build the root list first then do it here
        if (this.initiating) {
            if (buildRootList())
                this.initiating = false;
            else
                return;
        }

        HierarchyListItem curList = getCurrentList(true);
        if (curList instanceof HierarchyListItem2)
            ((HierarchyListItem2) curList).refresh(this.currFilter);
        else
            curList.refresh(this.currFilter.sort);
        notifyDataSetChanged();
    }

    /**
     * This method builds the root list of Overlay Manager
     * Each registered map overlay is scanned and its list model is pulled out
     * to be displayed in Overlay Manager
     * @return True if successs, false if failed (OM no longer active)
     */
    private boolean buildRootList() {
        if (!this.active)
            return false;

        final long actions = (userSelectHandler != null)
                ? userSelectHandler.getActions()
                : (Actions.ACTION_GOTO | Actions.ACTION_VISIBILITY);

        final List<HierarchyListItem> listModels = new ArrayList<>();
        Collection<MapOverlay> overlays = mapView.getMapOverlayManager()
                .getOverlays();
        for (MapOverlay overlay : overlays) {
            HierarchyListItem item;
            if (overlay instanceof MapOverlay2)
                item = ((MapOverlay2) overlay).getListModel(
                        HierarchyListAdapter.this, actions, this.currFilter);
            else
                item = overlay.getListModel(HierarchyListAdapter.this, actions,
                        this.currFilter.sort);
            if (item == null)
                continue;
            listModels.add(item);
        }
        ListModelImpl model = new ListModelImpl(HierarchyListAdapter.this,
                rootSearchEngine, currFilter);
        model.addAll(listModels);
        setModel(model);
        return true;
    }

    /**
     * apply a handler to the list, set the mode to select mode and refresh the list
     *
     * @param hlus - the handler of the list
     */
    public void setSelectHandler(HierarchyListUserSelect hlus) {
        unhighlight();
        this.userSelectHandler = hlus;
        resetFilter();

        // Back out of list where entry may not be allowed
        while (this.currentList != this.model &&
                !this.currFilter.acceptEntry(this.currentList))
            popList();

        // Update filter
        if (this.model != null)
            this.model.setFilter(this.currFilter);

        // Refresh view
        setHierManView(this.view);
        if (hlus == null)
            this.receiver.handlerFinished();
        this.receiver.refreshTitle();

        // Refresh list
        refreshList();
    }

    /**
     * Returns the current active user select handler. Will be <code>null</code>
     * for visibility toggle.
     *
     * @return  Returns the current active user select handler
     */
    public HierarchyListUserSelect getSelectHandler() {
        return userSelectHandler;
    }

    /**
     * remove any handler from the list and set the mode to visibility
     */
    void clearHandler() {
        this.userSelectHandler = null;
        if (this.model != null)
            this.model.setFilter(null);
        setHierManView(this.view);
        this.receiver.handlerFinished();
        this.receiver.refreshTitle();
        // TODO: See Bug 6589 - Calling this during export breaks it
        // The root issue is that we're creating new item instances every
        // refresh rather than using the existing ones
        refreshList();
    }

    /**
     * Return title of current list
     */
    public String getListTitle() {
        String title = null;
        if (userSelectHandler != null)
            title = userSelectHandler.getTitle();
        if (FileSystemUtils.isEmpty(title) && this.currentList != null)
            title = this.currentList.getTitle();
        return (FileSystemUtils.isEmpty(title)
                ? context.getString(R.string.overlay_manager)
                : title);
    }

    /**
     * Process selected items using user select handler
     */
    void processUserSelections() {
        final HierarchyListUserSelect handler = this.userSelectHandler;
        final List<String> paths = new ArrayList<>(this.selectedPaths);
        if (handler == null
                || (handler.getButtonMode() != ButtonMode.ALWAYS_VISIBLE
                        && paths.isEmpty()))
            return;
        new SelectionTask(paths, handler).execute();
    }

    /**
     * Cancel user selection and send a callback to the select handler
     */
    void cancelUserSelection() {
        if (userSelectHandler != null)
            userSelectHandler.cancel(context);
        clearHandler();
    }

    /**
     * Task for finding items based on selection paths
     */
    private class SelectionTask
            extends AsyncTask<Void, Integer, Set<HierarchyListItem>> {

        private final ProgressDialog pd;
        private final List<String> paths;
        private final HierarchyListUserSelect handler;

        private SelectionTask(List<String> paths, HierarchyListUserSelect h) {
            this.paths = paths;
            this.handler = h;
            this.pd = new ProgressDialog(context);
        }

        @Override
        protected void onPreExecute() {
            blockRefresh(true);
            pd.setTitle("Processing Selections");
            pd.setProgress(0);
            pd.setMax(paths.size());
            pd.setCancelable(false);
            pd.setIndeterminate(false);
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.show();
        }

        @Override
        public Set<HierarchyListItem> doInBackground(Void... params) {
            Set<HierarchyListItem> ret = new HashSet<>(
                    paths.size());
            int retryCount = 5;
            while (!paths.isEmpty() && --retryCount > 0) {
                find(paths, ret);
                if (!paths.isEmpty()) {
                    Log.w(TAG, "Failed to find " + paths.size()
                            + " checked items!");
                    for (String path : paths)
                        Log.w(TAG, path);
                    Log.w(TAG, "Retrying in 500ms...");
                    try {
                        Thread.sleep(500);
                    } catch (Exception ignore) {
                    }
                }
            }
            return ret;
        }

        private void find(List<String> paths, Set<HierarchyListItem> ret) {
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                String uid = path;
                if (path.contains("\\"))
                    uid = path.substring(path.lastIndexOf("\\") + 1);
                HierarchyListItem item = findListByPath(path);
                if (item != null && item.getUID().equals(uid)) {
                    ret.add(item);
                    paths.remove(i--);
                    publishProgress(paths.size());
                }
            }
        }

        @Override
        protected void onPostExecute(Set<HierarchyListItem> items) {
            this.pd.dismiss();
            Runnable onFinish = new Runnable() {
                @Override
                public void run() {
                    blockRefresh(false);
                    refreshList();
                }
            };
            if (handler instanceof AsyncListUserSelect) {
                ((AsyncListUserSelect) handler).processUserSelections(context,
                        items, onFinish);
            } else {
                handler.processUserSelections(context, items);
                onFinish.run();
            }
        }

        @Override
        public void onProgressUpdate(Integer... params) {
            this.pd.setProgress(this.pd.getMax() - params[0]);
        }
    }

    /**
     * Find list given a backslash delimited path
     *
     * @param root The top-level list to begin the search from
     * @param path The path string (usually returned by {@link #getCurrentPath})
     * @return The matching list item (or root if not found)
     */
    private HierarchyListItem findListByPath(HierarchyListItem root,
            String path) {
        HierarchyListItem last = root == null
                ? (this.prevListStack.empty() ? this.currentList
                        : this.prevListStack.get(0))
                : root;
        for (String dir : path.split("\\\\")) {
            if (dir.isEmpty())
                continue;
            boolean found = false;
            if (last instanceof AbstractHierarchyListItem2) {
                // Much faster search method than below
                HierarchyListItem item = ((AbstractHierarchyListItem2) last)
                        .findChild(dir);
                if (item != null) {
                    last = item;
                    found = true;
                }
            } else {
                List<HierarchyListItem> items = getChildren(last);
                for (HierarchyListItem item : items) {
                    if (item != null) {
                        String uid = item.getUID();
                        if (uid != null && uid.equals(dir)) {
                            last = item;
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (!found)
                break;
        }
        return last;
    }

    private HierarchyListItem findListByPath(String path) {
        return findListByPath(null, path);
    }

    public static List<HierarchyListItem> getChildren(HierarchyListItem list) {
        if (!list.isChildSupported())
            return new ArrayList<>();
        List<HierarchyListItem> children;
        boolean isList2 = list instanceof AbstractHierarchyListItem2;
        boolean gcSupported = isGetChildrenSupported(list);
        if (isList2 && gcSupported)
            children = ((AbstractHierarchyListItem2) list).getChildren();
        else if (isList2 || list instanceof FeatureSetHierarchyListItem) {
            // XXX - Use placeholder list since retrieving all children is VERY inefficient
            children = new ArrayList<>();
            int childCount = list.getChildCount();
            for (int i = 0; i < childCount; i++)
                children.add(new EmptyListItem());
        } else {
            children = new ArrayList<>();
            int childCount = list.getChildCount();
            for (int i = 0; i < childCount; i++) {
                HierarchyListItem child = list.getChildAt(i);
                if (child != null)
                    children.add(child);
            }
        }
        return children;
    }

    private static boolean isGetChildrenSupported(HierarchyListItem list) {
        return list instanceof AbstractHierarchyListItem2
                && ((AbstractHierarchyListItem2) list).isGetChildrenSupported();
    }

    /**
     * Set whether an item is checked or not
     * @param item List item
     * @param checked True if checked
     */
    public void setItemChecked(HierarchyListItem item, boolean checked) {

        // Allow select handler to override behavior
        if (this.userSelectHandler != null && (checked
                && this.userSelectHandler.onItemSelected(this, item)
                || !checked
                        && this.userSelectHandler.onItemDeselected(this, item)))
            return;

        // Simplified logic for top-level select all
        if (item == this.model) {
            this.selectedPaths.clear();
            if (checked) {
                List<HierarchyListItem> children = getChildren(this.model);
                for (HierarchyListItem c : children)
                    this.selectedPaths.add("\\" + c.getUID());
            }
            updateCheckAll();
            notifyDataSetChanged();
            return;
        }

        String path = getCurrentPath(item);
        List<String> toRemove = new ArrayList<>();
        Set<String> toAdd = new HashSet<>();

        // Before doing anything - remove self and child items
        for (String p : this.selectedPaths) {
            // Remove self and any child items
            if (withinDir(path, p))
                toRemove.add(p);
            else if (checked && withinDir(p, path)) {
                // Item is already covered by parent selection
                return;
            }
        }
        this.selectedPaths.removeAll(toRemove);

        if (checked) {
            // Add self to list
            toAdd.add(path);
        } else {
            // Remove self but maintain selected parent items
            for (String p : this.selectedPaths) {
                if (withinDir(p, path)) {
                    toRemove.add(p);
                    // Now we need to add all the sister items
                    // but only at the highest possible level without affecting
                    // the item we just deselected
                    toAdd.addAll(selectAllExclude(null, p, path));
                }
            }
        }
        this.selectedPaths.removeAll(toRemove);
        this.selectedPaths.addAll(toAdd);
        updateCheckAll();
        notifyDataSetChanged();
    }

    /**
     * Set the list of selected paths
     *
     * A path is made up of each list item UID separated by backslashes
     * i.e. \Markers\Mission\Marker-UUID
     *
     * @param paths Collection of paths to select
     */
    public void setSelectedPaths(Collection<String> paths) {
        this.selectedPaths.clear();
        this.selectedPaths.addAll(paths);
        updateCheckAll();
        notifyDataSetChanged();
    }

    /**
     * Get the set of paths currently selected
     * @return Selected path set
     */
    public Set<String> getSelectedPaths() {
        return new HashSet<>(this.selectedPaths);
    }

    /**
     * Select all items in the parent list hierarchy while excluding
     * a specific path
     *
     * @param parent Parent list (null to find using parent list path)
     * @param parentPath Parent list path
     * @param excludePath Excluded path
     * @return List of selected paths
     */
    private List<String> selectAllExclude(HierarchyListItem parent,
            String parentPath, String excludePath) {
        List<String> ret = new ArrayList<>();
        if (parentPath.equals(excludePath))
            return ret;
        if (parent == null) {
            parent = findListByPath(parentPath);
            if (parent == null)
                return ret;
        }
        if (parent != this.model)
            parentPath += "\\";
        List<HierarchyListItem> children = getChildren(parent);
        for (HierarchyListItem item : children) {
            String itemPath = parentPath + item.getUID();
            if (withinDir(itemPath, excludePath))
                ret.addAll(selectAllExclude(item, itemPath, excludePath));
            else
                ret.add(itemPath);
        }
        return ret;
    }

    /**
     * Updates the state of the "Select All" checkbox
     */
    private void updateCheckAll() {
        if (userSelectHandler != null && userSelectHandler
                .getButtonMode() == ButtonMode.VISIBLE_WHEN_SELECTED)
            processBtn.setVisibility(this.selectedPaths.isEmpty()
                    ? View.GONE
                    : View.VISIBLE);
        receiver.updateCheckAll(getCheckValue(null));
    }

    /**
     * Check if an item is selected
     *
     * @param item Item to check
     * @return True if selected
     */
    public boolean isChecked(HierarchyListItem item) {
        return getCheckValue(item) != UNCHECKED;
    }

    /**
     * Get the checked value for an item (CHECKED, UNCHECKED, or SEMI_CHECKED)
     *
     * @param item The item or list to check
     * @param itemPath The item path or null to find it
     * @return The checked value
     */
    private int getCheckValue(HierarchyListItem item, String itemPath) {
        // Run through selected paths to see if they contain this item
        if (item == null)
            item = this.currentList;
        if (itemPath == null)
            itemPath = getCurrentPath(item);
        for (String path : this.selectedPaths) {
            if (withinDir(path, itemPath))
                return CHECKED;
        }

        // At this point, if the item does not have children then it must be
        // unchecked
        List<HierarchyListItem> children;
        if (!item.isChildSupported()
                || FileSystemUtils.isEmpty(children = getChildren(item)))
            return UNCHECKED;

        String dirPath = itemPath;
        if (item != this.model)
            dirPath += "\\";
        boolean partial = false;
        boolean all = true;
        for (HierarchyListItem c : children) {
            String childPath = dirPath + c.getUID();
            int state = getCheckValue(c, childPath);
            partial |= (state != UNCHECKED);
            all &= (state == CHECKED);
        }

        return partial && all ? CHECKED : (partial ? SEMI_CHECKED : UNCHECKED);
    }

    /**
     * Determine the 3-state checked value of an item or list
     *
     * @param item List item
     * @return Check value (CHECKED, UNCHECKED, or SEMI_CHECKED)
     */
    public int getCheckValue(HierarchyListItem item) {
        // Nothing selected means it must be unchecked
        if (FileSystemUtils.isEmpty(this.selectedPaths))
            return UNCHECKED;
        return getCheckValue(item, null);
    }

    /**
     * Check if path is under a directory based on UID order
     *
     * @param dir Directory (i.e. "\Markers\Cot 2525B"
     * @param path Path (i.e. "\Markers\Cot 2525B\Hostile"
     * @return True if path is equal to or under dir, false otherwise
     */
    private boolean withinDir(String dir, String path) {
        return dir.equals("\\") || path.equals(dir) || path.startsWith(dir)
                && path.charAt(dir.length()) == '\\';
    }

    /**
     * Determine if the current list is the top-level OM list
     *
     * @return True if the list is at the root level, otherwise false
     */
    public boolean isRootList() {
        return this.prevListStack.size() < 1;
    }

    /**
     * Pop the current list out of the stack
     * Intentionally "public" for plugin use
     *
     * @param toList Optional list to enter instead of popped stack item
     */
    public void popList(final HierarchyListItem toList) {
        ((Activity) this.context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                searchResults = null;
                searchTerms = null;
                receiver.showSearchLoader(false);
                unhighlight();
                if (prevListStack.isEmpty())
                    return;

                // Fire close event for the old list
                HierarchyListStateListener l = getListStateListener();
                if (l != null && l.onCloseList(HierarchyListAdapter.this,
                        false))
                    return;

                // Fire open event for the new list
                HierarchyListItem list = toList != null ? toList
                        : prevListStack.peek();
                if (list instanceof HierarchyListStateListener
                        && ((HierarchyListStateListener) list)
                                .onOpenList(HierarchyListAdapter.this)) {
                    HierarchyListStateListener l2 = getListStateListener();
                    if (l2 != null && l2 == l)
                        // List hasn't actually changed - fire open on the old
                        // list again
                        l2.onOpenList(HierarchyListAdapter.this);
                    return;
                }

                prevListStack.pop();
                setCurrentList(list);
                if (!prevListScroll.isEmpty())
                    receiver.setScroll(prevListScroll.pop());
                if (prevListStack.size() < 1 && view != null) {
                    // tell the receiver that we're back to the root
                    receiver.backToRoot();
                }
                if (userSelectHandler != null && userSelectHandler
                        .isMultiSelect())
                    updateCheckAll();
                receiver.refreshTitle();
                receiver.setViewToMode();
                refreshList();
            }
        });
    }

    public void popList() {
        popList(null);
    }

    /**
     * Pushes the specified list onto the list view.
     *
     * @param item The list item
     * @param ifSublistExists If <code>true</code> the list will only be pushed if it has one or
     *            more children. If <code>false</code>, it will always be pushed.
     * @return The title of the current list
     */
    public String pushList(HierarchyListItem item, boolean ifSublistExists) {
        if (!isActive() || !this.currFilter.acceptEntry(item))
            return "";

        // Do not allow push if the "sublist exists" check is true and
        // the list is empty (and is hidden when empty)
        if (ifSublistExists && item.getChildCount() <= 0
                && (!(item instanceof HierarchyListItem2)
                        || ((HierarchyListItem2) item).hideIfEmpty()))
            return "";

        // Need to pop search results and then push
        if (this.currentList instanceof SearchResults) {
            searchTerms = null;
            popList(item);
            return item.getTitle();
        }

        // Fire close event for the old list
        HierarchyListStateListener l = null;
        if (!(item instanceof SearchResults)) {
            l = getListStateListener();
            if (l != null && l.onCloseList(this, false))
                return this.currentList.getTitle();
        }

        // Fire open event for the new list
        if (item instanceof HierarchyListStateListener
                && ((HierarchyListStateListener) item).onOpenList(this)) {
            HierarchyListStateListener l2 = getListStateListener();
            if (l2 != null && l2 == l)
                // List hasn't actually changed - fire open on the old
                // list again
                l2.onOpenList(HierarchyListAdapter.this);
            return this.currentList.getTitle();
        }

        // Push the new list
        this.prevListStack.push(this.currentList);
        this.prevListScroll.push(this.receiver.getScroll());
        setCurrentList(item);
        if (this.view != null)
            this.view.findViewById(R.id.hierarchy_back_button)
                    .setVisibility(View.VISIBLE);
        if (userSelectHandler != null && userSelectHandler
                .isMultiSelect())
            updateCheckAll();
        this.receiver.setViewToMode();
        return item.getTitle();
    }

    /**
     * Get the name of this map item
     * TODO: Also consolidate ATAKUtilities.getDisplayName
     * @param mapItem Map item
     * @param devCallsign Device callsign (optional; null to ignore)
     * @param devUID Device UID (optional; null to ignore)
     * @param baseGroup Parent map group (usually root group)
     * @return Map item title
     *
     * @deprecated Just use {@link ATAKUtilities#getDisplayName(MapItem)} instead
     */
    @Deprecated
    @DeprecatedApi(since = "4.1", forRemoval = true, removeAt = "4.4")
    public static String getTitleFromMapItem(MapItem mapItem,
            String devCallsign, String devUID, MapGroup baseGroup) {
        return ATAKUtilities.getDisplayName(mapItem);
    }

    /**
     * Toggle the green background highlight on an item
     *
     * @param item Item to toggle
     */
    public void toggleHighlight(HierarchyListItem item) {
        if (item != null && (this.highlightUID == null
                || !this.highlightUID.equals(item.getUID())))
            this.highlightUID = item.getUID();
        else
            this.highlightUID = null;
        notifyDataSetChanged();
    }

    /**
     * Remove highlight on any highlighted item
     */
    public void unhighlight() {
        if (this.highlightUID != null)
            toggleHighlight(null);
    }

    /**
     * Get the UID of the currently highlighted item
     *
     * @return Item UID
     */
    public String getHighlightUID() {
        return this.highlightUID;
    }

    /**
     * Update the active filter based on selected filter modes
     * (Show All, multi-select, etc.)
     */
    private void resetFilter() {
        List<HierarchyListFilter> filters = new ArrayList<>();
        Sort sort = HierarchyListReceiver.findSort(this.currentList,
                this.curSort);

        filters.add(this.filterFOV ? new FOVFilter(
                new FOVFilter.MapState(this.mapView), sort)
                : new HierarchyListFilter(sort));
        if (this.userSelectHandler != null)
            filters.add(this.userSelectHandler);
        this.currFilter = new MultiFilter(sort, filters);
    }

    /**
     * Return path to current list
     *
     * @param selected The item to find (must be under current list)
     * @return Path containing each list UID separated by slashes
     */
    public String getCurrentPath(HierarchyListItem selected) {
        List<String> uids = getCurrentPathList(selected);
        StringBuilder path = new StringBuilder("\\");
        for (int i = 0; i < uids.size(); i++) {
            path.append(uids.get(i));
            if (i < uids.size() - 1)
                path.append("\\");
        }
        return path.toString();
    }

    /**
     * Return path to the current list as an array of UIDs
     *
     * @param selected The item to find (must be under current list)
     * @return List of UIDs pointing to the selected item
     */
    public List<String> getCurrentPathList(HierarchyListItem selected) {
        List<String> uids = new ArrayList<>();
        for (HierarchyListItem list : this.prevListStack) {
            if (list == this.model)
                continue;
            uids.add(list.getUID());
        }
        if (this.currentList != this.model)
            uids.add(this.currentList.getUID());
        if (selected != null && selected != this.currentList)
            uids.add(selected.getUID());
        return uids;
    }

    /**
     * Get the current list
     *
     * @param nonSearch True to ignore the search screen
     * @return Current list
     */
    public HierarchyListItem getCurrentList(boolean nonSearch) {
        if (nonSearch && this.currentList instanceof SearchResults)
            return this.prevListStack.get(this.prevListStack.size() - 1);
        return this.currentList;
    }

    /**
     * Get the current list's state listener
     * Current list must implement {@link HierarchyListStateListener}
     *
     * @return The state listener or null if not supported
     */
    HierarchyListStateListener getListStateListener() {
        HierarchyListItem item = getCurrentList(true);
        return item instanceof HierarchyListStateListener
                ? (HierarchyListStateListener) item
                : null;
    }

    /**
     * End search mode (pop search list)
     */
    public void endSearch() {
        if (this.currentList instanceof SearchResults)
            popList();
        receiver.endSearch();
    }

    /**
     * Fired whenever the user moves the map view in some way
     * Used to update the "Show All" filtering
     *
     * @param view The map view
     * @param animate Smooth transition requested
     */
    @Override
    public void onMapMoved(AtakMapView view, boolean animate) {
        if ((filterFOV || currFilter != null
                && currFilter.sort instanceof SortDistanceFrom
                && ATAKUtilities.findSelf(mapView) == null))
            refreshList();
    }

    /**
     * Fired whenever the user's location changes
     * Used to update distances between items and the user
     *
     * @param item The self marker
     */
    @Override
    public void onPointChanged(PointMapItem item) {
        if (currFilter != null && currFilter.sort instanceof SortDistanceFrom)
            refreshList();
        else if (showingLocationItem)
            notifyDataSetChanged();
    }

    /**
     * Used to update the list items based on changes to R&B units
     *
     * @param p Preferences
     * @param key Preference key
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        // Preferred range system (metric, imperial, nautical)
        switch (key) {
            case "rab_rng_units_pref":
                rangeSystem = Integer.parseInt(p.getString(key,
                        String.valueOf(1)));
                break;

            // Bearing units (degrees, mils)
            case "rab_brg_units_pref":
                bearingUnits = Angle.findFromValue(Integer.parseInt(
                        p.getString(key, String.valueOf(0))));
                break;

            // North reference (true, magnetic, grid)
            case "rab_north_ref_pref":
                this.northRef = NorthReference.findFromValue(Integer.parseInt(
                        prefs.getString("rab_north_ref_pref", String.valueOf(
                                NorthReference.MAGNETIC.getValue()))));
                break;

            // Coordinate display (DD, DM, DMS, MGRS, UTM)
            case "coord_display_pref":
                coordFmt = CoordinateFormat.find(p.getString(key,
                        context.getString(
                                R.string.coord_display_pref_default)));
                break;

            // Ignore preference change
            default:
                return;
        }

        // Refresh list view
        notifyDataSetChanged();
    }

    /* Sub-classes */

    /**
     * Sorting by item distance from a point of interest (usually self location)
     */
    public final static class ItemDistanceComparator implements
            Comparator<HierarchyListItem> {
        private final GeoPoint _pointOfInterest;

        public ItemDistanceComparator(SortDistanceFrom sort) {
            this(sort.location);
        }

        public ItemDistanceComparator(GeoPoint pointOfInterest) {
            _pointOfInterest = pointOfInterest;
        }

        @Override
        public int compare(HierarchyListItem i1, HierarchyListItem i2) {
            final GeoPointMetaData p1 = getLocation(i1), p2 = getLocation(i2);
            if (p1 != null && p2 != null) {
                final double d1 = DistanceCalculations
                        .metersFromAtSourceTarget(p1.get(),
                                _pointOfInterest);
                final double d2 = DistanceCalculations
                        .metersFromAtSourceTarget(p2.get(),
                                _pointOfInterest);
                if (d1 > d2)
                    return 1;
                else if (d2 > d1)
                    return -1;
            } else if (p1 != null) {
                return -1;
            } else if (p2 != null) {
                return 1;
            }

            return MENU_ITEM_COMP.compare(i1, i2);
        }

        private GeoPointMetaData getLocation(HierarchyListItem item) {
            if (item instanceof ILocation) {
                return GeoPointMetaData.wrap(((ILocation) item).getPoint(null));
            } else if (item instanceof Location) {
                GeoPointMetaData point = ((Location) item).getLocation();
                return point;
            } else if (item instanceof MapItemUser) {
                MapItem mp = ((MapItemUser) item).getMapItem();
                if (mp != null) {
                    if (mp instanceof PointMapItem)
                        return ((PointMapItem) mp).getGeoPointMetaData();
                    else if (mp instanceof Shape)
                        return ((Shape) mp).getCenter();
                }
            }
            return null;
        }
    }

    /**
     * The top-level list model
     */
    private static class ListModelImpl extends AbstractHierarchyListItem2
            implements Search {

        private final HierarchyListAdapter om;
        private final Search search;
        private final List<HierarchyListItem> lists;
        private final Object filterLock = new Object();

        ListModelImpl(HierarchyListAdapter listener, Search search,
                HierarchyListFilter filter) {
            this.om = listener;
            this.search = search;
            this.listener = listener;
            this.lists = new ArrayList<>();
            setFilter(filter);
        }

        public void setFilter(HierarchyListFilter filter) {
            synchronized (filterLock) {
                this.filter = filter;
            }
        }

        public synchronized void clear() {
            synchronized (this.lists) {
                this.lists.clear();
            }
            onChildRefresh(true);
        }

        public synchronized void addAll(ListModelImpl other) {
            synchronized (other.lists) {
                addAll(other.lists);
            }
        }

        public synchronized void addAll(List<HierarchyListItem> items) {
            synchronized (this.lists) {
                this.lists.addAll(items);
            }
            onChildRefresh(true);
        }

        public synchronized List<HierarchyListItem> getLists() {
            synchronized (this.lists) {
                return new ArrayList<>(this.lists);
            }
        }

        /**********************************************************************/

        @Override
        public String getTitle() {
            return this.om.context.getString(R.string.overlay_manager);
        }

        @Override
        public int getDescendantCount() {
            return getChildCount();
        }

        private boolean visible(HierarchyListItem item) {
            synchronized (this.filterLock) {
                if (this.filter != null && !this.filter.accept(item))
                    return false;
                return postAccept(item);
            }
        }

        @Override
        public <T extends Action> T getAction(Class<T> clazz) {
            if (clazz.equals(Search.class))
                return clazz.cast(this.search);
            return null;
        }

        @Override
        public boolean hideIfEmpty() {
            return true;
        }

        @Override
        protected void refreshImpl() {
        }

        @Override
        public Object getUserObject() {
            return null;
        }

        @Override
        public View getExtraView() {
            return null;
        }

        @Override
        public HierarchyListFilter refresh(HierarchyListFilter filter) {
            this.filter = filter;
            List<HierarchyListItem> lists = getLists();
            for (HierarchyListItem i : lists)
                refresh(i, filter);
            return filter;
        }

        @Override
        public List<Sort> getSorts() {
            return new ArrayList<>();
        }

        /**
         * Function to call once a child has notified this adapter
         */
        void onChildRefresh(boolean notify) {
            // Filter
            List<HierarchyListItem> filtered = new ArrayList<>();
            List<HierarchyListItem> lists = getLists();
            for (HierarchyListItem item : lists) {
                if (visible(item))
                    filtered.add(item);
            }

            // Sort
            Collections.sort(filtered, MENU_ITEM_COMP);

            // Update
            synchronized (this.children) {
                // We don't want to call dispose here because the children
                // aren't re-created each refresh like usual
                // super.dispose() would clear out the items' children permanently
                this.children.clear();
                this.children.addAll(filtered);
                if (notify && this.listener != null)
                    this.listener.notifyDataSetChanged();
            }

        }

        @Override
        public void dispose() {
            List<HierarchyListItem> lists;
            synchronized (this.lists) {
                lists = new ArrayList<>(this.lists);
                this.lists.clear();
            }
            disposeItems(lists);
            super.dispose();
        }

        /**********************************************************************/
        // Action Search

        @Override
        public Set<HierarchyListItem> find(String term) {
            return this.search.find(term);
        }
    }

    // The sort method used for the top-level list
    public final static Comparator<HierarchyListItem> MENU_ITEM_COMP = new Comparator<HierarchyListItem>() {
        @Override
        public int compare(HierarchyListItem lhs, HierarchyListItem rhs) {
            final int lhsPreferredIndex = lhs.getPreferredListIndex();
            final int rhsPreferredIndex = rhs.getPreferredListIndex();
            if (lhsPreferredIndex >= 0 && rhsPreferredIndex >= 0) {
                int retval = lhsPreferredIndex - rhsPreferredIndex;
                if (retval != 0)
                    return retval;
            } else if (lhsPreferredIndex >= 0) {
                return -1;
            } else if (rhsPreferredIndex >= 0) {
                return 1;
            }
            final String lhsTitle = lhs.getTitle();
            final String rhsTitle = rhs.getTitle();
            if (lhsTitle == null)
                return 1;
            else if (rhsTitle == null)
                return -1;
            //return result if titles don't match
            int comp = lhsTitle.compareToIgnoreCase(rhsTitle);
            if (comp != 0)
                return comp;

            //finally fall back on UID to settle it
            final String lhsUID = lhs.getUID();
            final String rhsUiD = rhs.getUID();
            if (lhsUID == null && rhsUiD == null)
                return 0;
            if (lhsUID == null)
                return 1;
            else if (rhsUiD == null)
                return -1;
            return lhsUID.compareToIgnoreCase(rhsUiD);
        }
    };

    /**
     * The list used to display search results
     */
    private static class SearchResults extends AbstractHierarchyListItem2 {

        SearchResults(BaseAdapter listener, HierarchyListFilter filter) {
            this.listener = listener;
            this.filter = filter;
        }

        public void setResults(List<HierarchyListItem> items) {
            synchronized (this.children) {
                dispose();
                this.children.addAll(items);
                if (this.listener != null)
                    this.listener.notifyDataSetChanged();
            }
        }

        public void setResults(SearchResults other) {
            synchronized (other.children) {
                updateChildren(other.children);
            }
        }

        @Override
        public String getTitle() {
            return "";
        }

        @Override
        public int getDescendantCount() {
            return getChildCount();
        }

        @Override
        public <T extends Action> T getAction(Class<T> clazz) {
            return null;
        }

        @Override
        public Object getUserObject() {
            return null;
        }

        @Override
        public View getExtraView() {
            return null;
        }

        @Override
        public boolean isMultiSelectSupported() {
            return false;
        }

        @Override
        public void dispose() {
            synchronized (this.children) {
                this.children.clear();
                if (this.listener != null)
                    this.listener.notifyDataSetChanged();
            }
        }

        @Override
        protected void refreshImpl() {
        }

        @Override
        public boolean hideIfEmpty() {
            return true;
        }
    }

    /**
     * Generic list item used to represent a location
     * XXX - Is this ever used?
     */
    private static class LocationHierarchyListItem extends
            AbstractHierarchyListItem
            implements GoTo {

        private final Location _impl;

        LocationHierarchyListItem(Location impl) {
            _impl = impl;
        }

        @Override
        public String getTitle() {
            return _impl.getFriendlyName();
        }

        @Override
        public int getChildCount() {
            return 0;
        }

        @Override
        public int getDescendantCount() {
            return 0;
        }

        @Override
        public HierarchyListItem getChildAt(int index) {
            return null;
        }

        @Override
        public boolean isChildSupported() {
            return false;
        }

        @Override
        public String getIconUri() {
            return null;
        }

        @Override
        public <T extends Action> T getAction(Class<T> clazz) {
            if (clazz.equals(GoTo.class))
                return clazz.cast(this);
            return null;
        }

        @Override
        public Object getUserObject() {
            return _impl;
        }

        @Override
        public View getExtraView() {
            return null;
        }

        @Override
        public Sort refresh(Sort sort) {
            return sort;
        }

        /*********************************************************************/
        // Action Go To

        @Override
        public boolean goTo(boolean select) {
            GeoPointMetaData zoomLoc = _impl.getLocation();
            Intent intent = new Intent(
                    "com.atakmap.android.maps.ZOOM_TO_LAYER");
            intent.putExtra("point", zoomLoc.get().toString());

            // broadcast intent
            AtakBroadcast.getInstance().sendBroadcast(intent);

            return true;
        }
    }

    /**
     * Empty list item used as a placeholder for feature set content
     */
    private static class EmptyListItem extends AbstractHierarchyListItem {

        @Override
        public String getTitle() {
            return null;
        }

        @Override
        public View getExtraView() {
            return null;
        }

        @Override
        public boolean isChildSupported() {
            return false;
        }

        @Override
        public int getChildCount() {
            return 0;
        }

        @Override
        public int getDescendantCount() {
            return 0;
        }

        @Override
        public Sort refresh(Sort sort) {
            return sort;
        }

        @Override
        public Object getUserObject() {
            return this;
        }

        @Override
        public HierarchyListItem getChildAt(int index) {
            return null;
        }

        @Override
        public <T extends Action> T getAction(Class<T> clazz) {
            return null;
        }
    }

    private final Map<String, Long> startTimes = new HashMap<>();

    private void beginTimeMeasure(String tag) {
        this.startTimes.put(tag, SystemClock.elapsedRealtime());
    }

    private void endTimeMeasure(String tag, int iter) {
        Long startTime = this.startTimes.get(tag);
        if (startTime != null)
            Log.d(TAG, tag + " (" + (SystemClock.elapsedRealtime() - startTime)
                    + "ms)" + (iter != -1 ? " " + iter : ""));
    }

    private void endTimeMeasure(String tag) {
        endTimeMeasure(tag, -1);
    }
}
