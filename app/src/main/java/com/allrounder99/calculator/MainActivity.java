package com.allrounder99.calculator;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MainActivity extends Activity {
    private NativeCalculatorView calculatorView;
    private SharedPreferences preferences;
    private long lastBackPress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 27, 41));
        getWindow().setNavigationBarColor(Color.rgb(3, 27, 41));
        preferences = getSharedPreferences("allrounder99_native", MODE_PRIVATE);
        calculatorView = new NativeCalculatorView(this);
        setContentView(calculatorView);
    }

    List<String> history() {
        String saved = preferences.getString("history", "");
        if (saved == null || saved.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(saved.split("\\n")));
    }

    void addHistory(String category, String calculation) {
        List<String> items = history();
        items.add(0, category + "|" + calculation.replace('\n', ' '));
        while (items.size() > 40) items.remove(items.size() - 1);
        preferences.edit().putString("history", join(items)).apply();
    }

    void clearHistory() {
        preferences.edit().remove("history").apply();
        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
        calculatorView.invalidate();
    }

    private String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) { if (out.length() > 0) out.append('\n'); out.append(value); }
        return out.toString();
    }

    void showTool(final String tool) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(22));
        root.setBackground(round(Color.rgb(4, 38, 55), 26));
        root.addView(label(tool + " Calculator", 25, Color.WHITE, true), matchWrap());
        TextView subtitle = label(toolHint(tool), 14, Color.rgb(130, 186, 210), false);
        LinearLayout.LayoutParams subtitleLp = matchWrap(); subtitleLp.setMargins(0, dp(4), 0, dp(15)); root.addView(subtitle, subtitleLp);

        final List<EditText> fields = new ArrayList<>();
        for (String hint : fieldHints(tool)) {
            EditText input = new EditText(this);
            input.setHint(hint); input.setHintTextColor(Color.rgb(117, 164, 184)); input.setTextColor(Color.WHITE);
            input.setTextSize(17); input.setSingleLine(true); input.setPadding(dp(15), 0, dp(15), 0);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setBackground(round(Color.rgb(7, 55, 75), 16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)); lp.setMargins(0, 0, 0, dp(10));
            root.addView(input, lp); fields.add(input);
        }

        final TextView result = label("Enter values to calculate", 18, Color.WHITE, true);
        result.setGravity(Gravity.CENTER); result.setBackground(round(Color.rgb(7, 55, 75), 18)); result.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams resultLp = matchWrap(); resultLp.setMargins(0, dp(2), 0, dp(12)); root.addView(result, resultLp);
        Button calculate = new Button(this); calculate.setText("Calculate"); calculate.setTextSize(16); calculate.setTextColor(Color.rgb(1, 32, 47)); calculate.setAllCaps(false);
        calculate.setBackground(round(Color.rgb(11, 204, 246), 18));
        root.addView(calculate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        calculate.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            try { String answer = calculateTool(tool, fields); result.setText(answer); addHistory(tool, answer); }
            catch (Exception error) { result.setText("Please enter valid values"); }
        }});

        dialog.setContentView(root);
        dialog.setOnShowListener(d -> { Window w = dialog.getWindow(); if (w != null) { w.setBackgroundDrawableResource(android.R.color.transparent); w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * .92f), ViewGroup.LayoutParams.WRAP_CONTENT); } });
        dialog.show();
    }

    private String[] fieldHints(String tool) {
        if (tool.equals("GST")) return new String[]{"Amount", "GST rate %"};
        if (tool.equals("EMI")) return new String[]{"Loan amount", "Annual interest %", "Months"};
        if (tool.equals("Profit")) return new String[]{"Cost price", "Selling price"};
        if (tool.equals("Discount")) return new String[]{"Original price", "Discount %"};
        if (tool.equals("Interest")) return new String[]{"Principal", "Annual rate %", "Years"};
        if (tool.equals("Commission")) return new String[]{"Sales amount", "Commission %"};
        return new String[]{"Amount", "Conversion rate"};
    }

    private String toolHint(String tool) {
        if (tool.equals("GST")) return "Tax amount and final total";
        if (tool.equals("EMI")) return "Monthly loan instalment";
        if (tool.equals("Profit")) return "Profit, margin and markup";
        if (tool.equals("Discount")) return "Savings and final price";
        if (tool.equals("Interest")) return "Simple interest calculation";
        if (tool.equals("Commission")) return "Commission and net amount";
        return "Offline rate conversion";
    }

    private String calculateTool(String tool, List<EditText> f) {
        MathContext mc = new MathContext(20, RoundingMode.HALF_UP);
        BigDecimal a = value(f.get(0)), b = value(f.get(1));
        if (tool.equals("GST")) { BigDecimal tax=a.multiply(b,mc).divide(BigDecimal.valueOf(100),mc); return "GST: "+money(tax)+"  •  Total: "+money(a.add(tax)); }
        if (tool.equals("EMI")) { double p=a.doubleValue(), r=b.doubleValue()/1200d, m=value(f.get(2)).doubleValue(); double emi=r==0?p/m:p*r*Math.pow(1+r,m)/(Math.pow(1+r,m)-1); return "Monthly EMI: "+money(BigDecimal.valueOf(emi))+"  •  Total: "+money(BigDecimal.valueOf(emi*m)); }
        if (tool.equals("Profit")) { BigDecimal profit=b.subtract(a), margin=b.signum()==0?BigDecimal.ZERO:profit.multiply(BigDecimal.valueOf(100)).divide(b,mc), markup=a.signum()==0?BigDecimal.ZERO:profit.multiply(BigDecimal.valueOf(100)).divide(a,mc); return "Profit: "+money(profit)+"  •  Margin: "+number(margin)+"%  •  Markup: "+number(markup)+"%"; }
        if (tool.equals("Discount")) { BigDecimal saved=a.multiply(b,mc).divide(BigDecimal.valueOf(100),mc); return "You save: "+money(saved)+"  •  Final price: "+money(a.subtract(saved)); }
        if (tool.equals("Interest")) { BigDecimal years=value(f.get(2)), interest=a.multiply(b,mc).multiply(years,mc).divide(BigDecimal.valueOf(100),mc); return "Interest: "+money(interest)+"  •  Total: "+money(a.add(interest)); }
        if (tool.equals("Commission")) { BigDecimal commission=a.multiply(b,mc).divide(BigDecimal.valueOf(100),mc); return "Commission: "+money(commission)+"  •  Balance: "+money(a.subtract(commission)); }
        return "Converted amount: " + money(a.multiply(b, mc));
    }

    private BigDecimal value(EditText field) { return new BigDecimal(field.getText().toString().trim()); }
    private String money(BigDecimal v) { return "₹" + number(v); }
    private String number(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private TextView label(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(android.graphics.Typeface.DEFAULT,bold?1:0);return t;}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onBackPressed() {
        if (calculatorView.currentPage() != NativeCalculatorView.PAGE_HOME) { calculatorView.goHome(); return; }
        long now=System.currentTimeMillis();
        if(now-lastBackPress<1800)super.onBackPressed(); else{lastBackPress=now;Toast.makeText(this,"Press back again to exit",Toast.LENGTH_SHORT).show();}
    }
}
