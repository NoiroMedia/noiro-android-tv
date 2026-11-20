package tv.noiro.launcher;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.TextView;

/** Shared Noiro typography, rounded panels and visible TV focus for player menus. */
final class NoiroMenus {
    static void apply(AlertDialog dialog, float scale) {
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawable(dialog.getContext().getDrawable(R.drawable.noiro_menu_panel));
        window.getDecorView().setPadding(0,0,0,0);
        window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        android.view.WindowManager.LayoutParams at=window.getAttributes();at.y=Math.round(24*scale);window.setAttributes(at);
        window.setLayout(Math.min(Math.round(1060*scale),dialog.getContext().getResources().getDisplayMetrics().widthPixels-Math.round(48*scale)),ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(.32f);
        styleText(window.getDecorView(),scale);
        int titleId=dialog.getContext().getResources().getIdentifier("alertTitle","id","android");
        TextView title=window.findViewById(titleId);
        if(title!=null){title.setTypeface(Ui.displaySemibold);title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,29*scale);title.setTextColor(0xFFF4F5FA);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);}
        for(String name:new String[]{"titleDividerNoCustom","titleDividerTop","titleDivider"}){
            View divider=window.findViewById(dialog.getContext().getResources().getIdentifier(name,"id","android"));if(divider!=null)divider.setVisibility(View.GONE);
        }
        ListView list=dialog.getListView();
        if(list!=null){
            list.setDivider(new ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(Math.round(6*scale));
            list.setPadding(Math.round(16*scale),Math.round(8*scale),Math.round(16*scale),Math.round(12*scale));
            list.setSelector(dialog.getContext().getDrawable(R.drawable.noiro_menu_focus));list.setDrawSelectorOnTop(false);
            java.util.WeakHashMap<View,Boolean> styled=new java.util.WeakHashMap<>();
            list.getViewTreeObserver().addOnGlobalLayoutListener(()->{
                for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);if(!styled.containsKey(child)){styled.put(child,true);styleText(child,scale);}}
            });
        }
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            TextView button=dialog.getButton(which);if(button==null)continue;
            button.setAllCaps(false);button.setTypeface(Ui.semibold);button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,23*scale);
            button.setBackground(dialog.getContext().getDrawable(R.drawable.noiro_menu_button));
            button.setTextColor(dialog.getContext().getColorStateList(R.color.noiro_menu_button_text));
            button.setPadding(Math.round(24*scale),Math.round(12*scale),Math.round(24*scale),Math.round(12*scale));
        }
    }
    private static void styleText(View view,float scale){
        if(view instanceof TextView){
            TextView text=(TextView)view;text.setTypeface(Ui.medium);
            if(view instanceof CheckedTextView){text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,24*scale);text.setMinHeight(Math.round(58*scale));text.setPadding(Math.round(22*scale),Math.round(14*scale),Math.round(22*scale),Math.round(14*scale));}
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleText(group.getChildAt(i),scale);}
    }
}
