package configure;

import java.util.HashMap;
import java.util.HashSet;

public class Configure {
    private HashMap<String, HashSet<String>> apiMap = new HashMap<String, HashSet<String>>();

    private static Configure instance = null;

    public static Configure getInstance() {
        if (instance == null) {
            instance = new Configure();
        }
        return instance;
    }

    public HashMap<String, HashSet<String>> getApiMap() {
        return apiMap;
    }

    public Configure() {

        apiMap.put("setOnCapturedPointerListener", new HashSet<String>() {{
            add("onCapturedPointer");
        }});

        apiMap.put("setOnClickListener", new HashSet<String>() {{
            add("onClick");
        }});

        apiMap.put("setOnContextClickListener", new HashSet<String>() {{
            add("onContextClick");
        }});

        apiMap.put("setOnDragListener", new HashSet<String>() {{
            add("onDrag");
        }});

        apiMap.put("setOnFocusChangeListener", new HashSet<String>() {{
            add("onFocusChange");
        }});

        apiMap.put("setOnGenericMotionListener", new HashSet<String>() {{
            add("onGenericMotion");
        }});

        apiMap.put("setOnHoverListener", new HashSet<String>() {{
            add("onHover");
        }});

        apiMap.put("setOnKeyListener", new HashSet<String>() {{
            add("onKey");
        }});

        apiMap.put("setOnLongClickListener", new HashSet<String>() {{
            add("onLongClick");
        }});

        apiMap.put("setOnReceiveContentListener", new HashSet<String>() {{
            add("onReceiveContent");
        }});

        apiMap.put("setOnScrollChangeListener", new HashSet<String>() {{
            add("onScrollChange");
        }});

        apiMap.put("setOnTouchListener", new HashSet<String>() {{
            add("onTouch");
        }});

        apiMap.put("setOnEditorActionListener", new HashSet<String>() {{
            add("onEditorAction");
        }});

        apiMap.put("setOnInflateListener", new HashSet<String>() {{
            add("onInflate");
        }});

        apiMap.put("setOnScrollListener", new HashSet<String>() {{
            add("onScroll");
            add("onScrollStateChanged");
        }});

        apiMap.put("setOnMenuItemClickListener", new HashSet<String>() {{
            add("onMenuItemClick");
        }});

        apiMap.put("setOnItemClickListener", new HashSet<String>() {{
            add("onItemClick");
        }});

        apiMap.put("setOnItemLongClickListener", new HashSet<String>() {{
            add("onItemLongClick");
        }});

        apiMap.put("setOnItemSelectedListener", new HashSet<String>() {{
            add("onItemSelected");
            add("onNothingSelected");
        }});

        apiMap.put("setOnChronometerTickListener", new HashSet<String>() {{
            add("onChronometerTick");
        }});

        apiMap.put("setOnDateChangedListener", new HashSet<String>() {{
            add("onDateChanged");
        }});

        apiMap.put("setOnChildClickListener", new HashSet<String>() {{
            add("onChildClick");
        }});

        apiMap.put("setOnGroupClickListener", new HashSet<String>() {{
            add("onGroupClick");
        }});

        apiMap.put("setOnGroupCollapseListener", new HashSet<String>() {{
            add("onGroupCollapse");
        }});

        apiMap.put("setOnGroupExpandListener", new HashSet<String>() {{
            add("onGroupExpand");
        }});

        apiMap.put("setOnValueChangedListener", new HashSet<String>() {{
            add("onValueChange");
        }});

        apiMap.put("setOnRatingBarChangeListener", new HashSet<String>() {{
            add("onRatingChanged");
        }});

        apiMap.put("setOnCloseListener", new HashSet<String>() {{
            add("onClose");
        }});

        apiMap.put("setOnQueryTextFocusChangeListener", new HashSet<String>() {{
            add("onFocusChange");
        }});

        apiMap.put("setOnQueryTextListener", new HashSet<String>() {{
            add("onQueryTextChange");
            add("onQueryTextSubmit");
        }});

        apiMap.put("setOnSearchClickListener", new HashSet<String>() {{
            add("onClick");
        }});

        apiMap.put("setOnSuggestionListener", new HashSet<String>() {{
            add("onSuggestionClick");
            add("onSuggestionSelect");
        }});

        apiMap.put("setOnSeekBarChangeListener", new HashSet<String>() {{
            add("onProgressChanged");
            add("onStartTrackingTouch");
            add("onStopTrackingTouch");
        }});

        apiMap.put("setOnDrawerCloseListener", new HashSet<String>() {{
            add("onDrawerClosed");
        }});

        apiMap.put("setOnDrawerOpenListener", new HashSet<String>() {{
            add("onDrawerOpened");
        }});

        apiMap.put("setOnDrawerScrollListener", new HashSet<String>() {{
            add("onScrollEnded");
            add("onScrollStarted");
        }});

        apiMap.put("setOnItemClickListener", new HashSet<String>() {{
            add("onItemClick");
        }});

        apiMap.put("setOnItemSelectedListener", new HashSet<String>() {{
            add("onItemSelected");
            add("onNothingSelected");
        }});

        apiMap.put("setOnTabChangedListener", new HashSet<String>() {{
            add("onTabChanged");
        }});

        apiMap.put("setOnTabSelectionChanged", new HashSet<String>() {{
            add("onTabSelectionChanged");
        }});

        apiMap.put("setOnTimeChangedListener", new HashSet<String>() {{
            add("onTimeChanged");
        }});

        apiMap.put("setOnTimeChangedListener", new HashSet<String>() {{
            add("onTimeChanged");
        }});

        apiMap.put("setOnMenuItemClickListener", new HashSet<String>() {{
            add("onMenuItemClick");
        }});

        apiMap.put("setOnCompletionListener", new HashSet<String>() {{
            add("onCompletion");
        }});

    }
}
