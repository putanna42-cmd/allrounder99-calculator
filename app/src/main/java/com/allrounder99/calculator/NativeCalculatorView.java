package com.allrounder99.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class NativeCalculatorView extends View {
    static final int PAGE_HOME = 0, PAGE_CALCULATOR = 1, PAGE_HISTORY = 2;
    private static final int MAX_DIGITS = 100, MAX_EXPRESSION = 220;
    private final MainActivity activity;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Hit> hits = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Bitmap logo;
    private int page = PAGE_HOME;
    private boolean lightTheme, scientific;
    private String expression = "";
    private String status = "Ready";
    private RectF displayRect = new RectF();
    private float density, downX, downY, lastX, displayOffset, historyOffset;
    private boolean draggingDisplay, draggingHistory;
    private Hit pressed;
    private Runnable repeatDelete;

    NativeCalculatorView(MainActivity context) {
        super(context);
        activity = context;
        density = getResources().getDisplayMetrics().density;
        logo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setBackgroundColor(bg());
    }

    int currentPage() { return page; }
    void goHome() { if (page != PAGE_HOME) { page = PAGE_HOME; historyOffset = 0; invalidate(); } }
    void showCalculator() { page = PAGE_CALCULATOR; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        hits.clear();
        canvas.drawColor(bg());
        drawHeader(canvas);
        if (page == PAGE_HOME) drawHome(canvas);
        else if (page == PAGE_CALCULATOR) drawCalculator(canvas);
        else drawHistory(canvas);
        drawNavigation(canvas);
    }

    private void drawHeader(Canvas c) {
        float h = dp(86), pad = dp(18), icon = dp(52);
        paint.setColor(header()); c.drawRect(0, 0, getWidth(), h, paint);
        c.drawBitmap(logo, null, new RectF(pad, dp(16), pad + icon, dp(16) + icon), paint);
        text(c, "Allrounder99", pad + icon + dp(12), dp(40), dp(22), text(), Paint.Align.LEFT, true);
        text(c, "Calculator", pad + icon + dp(12), dp(64), dp(16), muted(), Paint.Align.LEFT, false);
        RectF theme = new RectF(getWidth() - dp(68), dp(18), getWidth() - dp(18), dp(68));
        round(c, theme, dp(16), card(), border());
        text(c, lightTheme ? "☾" : "☀", theme.centerX(), theme.centerY() + dp(8), dp(25), text(), Paint.Align.CENTER, false);
        hits.add(new Hit(theme, "theme", ""));
    }

    private void drawHome(Canvas c) {
        float top = dp(105), side = dp(18), gap = dp(11);
        text(c, "Business & Everyday Tools", side, top, dp(27), text(), Paint.Align.LEFT, true);
        text(c, "Fast native calculations — fully offline", side, top + dp(28), dp(14), muted(), Paint.Align.LEFT, false);
        String[][] tools = {
                {"▦", "Calculator", "Basic & scientific", "calculator"}, {"%", "GST", "Add or remove tax", "GST"},
                {"₹", "EMI & Loan", "Monthly instalment", "EMI"}, {"↗", "Profit", "Margin & markup", "Profit"},
                {"−", "Discount", "Price after discount", "Discount"}, {"◷", "Interest", "Simple & compound", "Interest"},
                {"₹", "Commission", "Sales commission", "Commission"}, {"⇄", "Currency", "Offline conversion", "Currency"}
        };
        float y = top + dp(50), cardW = (getWidth() - side * 2 - gap) / 2f;
        float bottom = getHeight() - dp(80), cardH = Math.min(dp(116), (bottom - y - gap * 3) / 4f);
        for (int i = 0; i < tools.length; i++) {
            int row = i / 2, col = i % 2;
            RectF r = new RectF(side + col * (cardW + gap), y + row * (cardH + gap), side + col * (cardW + gap) + cardW, y + row * (cardH + gap) + cardH);
            round(c, r, dp(22), card(), border());
            text(c, tools[i][0], r.left + dp(18), r.top + dp(37), dp(25), accent(), Paint.Align.LEFT, true);
            text(c, tools[i][1], r.left + dp(18), r.top + dp(66), dp(17), text(), Paint.Align.LEFT, true);
            text(c, tools[i][2], r.left + dp(18), r.top + dp(88), dp(11), muted(), Paint.Align.LEFT, false);
            hits.add(new Hit(r, "tool", tools[i][3]));
        }
    }

    private void drawCalculator(Canvas c) {
        float side = dp(18), top = dp(98), navTop = getHeight() - dp(78);
        float available = navTop - top;
        float displayH = Math.max(dp(122), Math.min(dp(168), available * .25f));
        displayRect.set(side, top, getWidth() - side, top + displayH);
        round(c, displayRect, dp(28), card(), border());
        text(c, status, displayRect.right - dp(20), displayRect.top + dp(31), dp(14), muted(), Paint.Align.RIGHT, false);
        drawScrollableText(c, expression.isEmpty() ? "0" : expression, displayRect, displayRect.top + displayH * .58f, text(), true);
        String preview = CalculatorEngine.preview(expression);
        drawScrollableText(c, preview, displayRect, displayRect.bottom - dp(18), muted(), false);

        float tabsTop = displayRect.bottom + dp(9), tabsH = dp(45);
        RectF basic = new RectF(side, tabsTop, getWidth()/2f, tabsTop + tabsH);
        RectF sci = new RectF(getWidth()/2f, tabsTop, getWidth() - side, tabsTop + tabsH);
        round(c, new RectF(side, tabsTop, getWidth()-side, tabsTop+tabsH), dp(18), surface(), border());
        if (!scientific) round(c, inset(basic, dp(4)), dp(15), selected(), Color.TRANSPARENT);
        else round(c, inset(sci, dp(4)), dp(15), selected(), Color.TRANSPARENT);
        text(c, "Basic", basic.centerX(), basic.centerY()+dp(6), dp(17), !scientific?accent():muted(), Paint.Align.CENTER, true);
        text(c, "Scientific", sci.centerX(), sci.centerY()+dp(6), dp(17), scientific?accent():muted(), Paint.Align.CENTER, true);
        hits.add(new Hit(basic,"mode","basic")); hits.add(new Hit(sci,"mode","scientific"));

        float gridTop = tabsTop + tabsH + dp(8);
        if (scientific) {
            String[] sf = {"sin", "cos", "tan", "√", "log"};
            float sw = (getWidth()-side*2-dp(6)*4)/5f;
            for(int i=0;i<5;i++) {
                RectF r=new RectF(side+i*(sw+dp(6)),gridTop,side+i*(sw+dp(6))+sw,gridTop+dp(42));
                round(c,r,dp(14),surface(),border()); text(c,sf[i],r.centerX(),r.centerY()+dp(5),dp(14),accent(),Paint.Align.CENTER,true);
                String key=sf[i].equals("√")?"sqrt(":sf[i]+"("; hits.add(new Hit(r,"key",key));
            }
            gridTop += dp(48);
        }
        String[][] keys = {
                {"AC","⌫","%","÷"},{"7","8","9","×"},{"4","5","6","−"},{"1","2","3","+"},{"±","0",".","="}
        };
        float gap = dp(7), gridBottom = navTop - dp(8), cellW=(getWidth()-side*2-gap*3)/4f, cellH=(gridBottom-gridTop-gap*4)/5f;
        for(int row=0;row<5;row++) for(int col=0;col<4;col++) {
            float l=side+col*(cellW+gap), t=gridTop+row*(cellH+gap);
            RectF r=new RectF(l,t,l+cellW,t+cellH); String label=keys[row][col];
            boolean op=col==3; int fill=op?operator():surface();
            round(c,r,Math.min(dp(30),cellH/2),fill,op?Color.TRANSPARENT:border());
            int color=(row==0&&col<3)?accent():text();
            text(c,label,r.centerX(),r.centerY()+dp(9),dp(25),color,Paint.Align.CENTER,true);
            String type="key", value=label;
            if(label.equals("AC")){type="action";value="clear";} else if(label.equals("⌫")){type="action";value="back";}
            else if(label.equals("±")){type="action";value="sign";} else if(label.equals("=")){type="action";value="equals";}
            hits.add(new Hit(r,type,value));
        }
    }

    private void drawScrollableText(Canvas c, String value, RectF area, float baseline, int color, boolean main) {
        if (value == null || value.isEmpty()) return;
        float size = main ? dp(38) : dp(23);
        if (value.length() > 28) size = main ? dp(23) : dp(17);
        if (value.length() > 55) size = main ? dp(17) : dp(14);
        paint.setTextSize(size); paint.setTypeface(android.graphics.Typeface.create("sans", main ? 1 : 0));
        float width=paint.measureText(value), usable=area.width()-dp(40), max=Math.max(0,width-usable);
        displayOffset=Math.max(0,Math.min(displayOffset,max));
        float x=area.right-dp(20)-width+displayOffset;
        c.save(); c.clipRect(area.left+dp(20),area.top+dp(36),area.right-dp(20),area.bottom-dp(8));
        paint.setColor(color); paint.setTextAlign(Paint.Align.LEFT); c.drawText(value,x,baseline,paint); c.restore();
    }

    private void drawHistory(Canvas c) {
        float side=dp(18), top=dp(112), nav=getHeight()-dp(78);
        text(c,"Calculation History",side,top,dp(27),text(),Paint.Align.LEFT,true);
        List<String> list=activity.history();
        if(list.isEmpty()) { text(c,"No calculations yet",getWidth()/2f,(top+nav)/2,dp(17),muted(),Paint.Align.CENTER,false); return; }
        float y=top+dp(30)-historyOffset;
        c.save();c.clipRect(0,top+dp(36),getWidth(),nav);
        for(String item:list) {
            RectF r=new RectF(side,y,getWidth()-side,y+dp(66));round(c,r,dp(17),card(),border());
            String[] p=item.split("\\|",2); text(c,p[0],r.left+dp(16),r.top+dp(27),dp(13),muted(),Paint.Align.LEFT,false);
            text(c,p.length>1?p[1]:"",r.left+dp(16),r.top+dp(51),dp(16),text(),Paint.Align.LEFT,true);y+=dp(75);
        }
        c.restore();
        RectF clear=new RectF(getWidth()-dp(128),top-dp(22),getWidth()-side,top+dp(14));round(c,clear,dp(14),Color.rgb(104,31,39),Color.TRANSPARENT);
        text(c,"Clear",clear.centerX(),clear.centerY()+dp(5),dp(13),Color.WHITE,Paint.Align.CENTER,true);hits.add(new Hit(clear,"action","clearHistory"));
    }

    private void drawNavigation(Canvas c) {
        float top=getHeight()-dp(78);paint.setColor(header());c.drawRect(0,top,getWidth(),getHeight(),paint);
        paint.setColor(border());c.drawRect(0,top,getWidth(),top+dp(1),paint);
        String[] icons={"⌂","⌗","◷"}, labels={"Home","Calculator","History"}; int[] pages={PAGE_HOME,PAGE_CALCULATOR,PAGE_HISTORY};
        for(int i=0;i<3;i++){
            float l=i*getWidth()/3f,r=(i+1)*getWidth()/3f,cx=(l+r)/2;int color=page==pages[i]?accent():muted();
            text(c,icons[i],cx,top+dp(31),dp(25),color,Paint.Align.CENTER,false);text(c,labels[i],cx,top+dp(57),dp(12),color,Paint.Align.CENTER,page==pages[i]);
            hits.add(new Hit(new RectF(l,top,r,getHeight()),"page",String.valueOf(pages[i])));
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(),y=e.getY();
        if(e.getAction()==MotionEvent.ACTION_DOWN){
            downX=lastX=x;downY=y;draggingDisplay=page==PAGE_CALCULATOR&&displayRect.contains(x,y);draggingHistory=page==PAGE_HISTORY;
            pressed=findHit(x,y);
            if(pressed!=null){performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);activate(pressed);if(pressed.type.equals("action")&&pressed.value.equals("back"))startDeleteRepeat();}
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_MOVE){
            float dx=x-lastX;
            if(draggingDisplay){displayOffset+=dx;invalidate();}
            else if(draggingHistory){historyOffset=Math.max(0,historyOffset-(y-downY));downY=y;invalidate();}
            lastX=x;return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){stopDeleteRepeat();pressed=null;draggingDisplay=draggingHistory=false;return true;}
        return true;
    }

    private void activate(Hit h) {
        switch(h.type){
            case "theme": lightTheme=!lightTheme;setBackgroundColor(bg());invalidate();break;
            case "page": page=Integer.parseInt(h.value);historyOffset=0;invalidate();break;
            case "mode": scientific=h.value.equals("scientific");invalidate();break;
            case "tool": if(h.value.equals("calculator"))showCalculator();else activity.showTool(h.value);break;
            case "key": addKey(h.value);break;
            case "action": action(h.value);break;
        }
    }

    private void addKey(String key) {
        if(expression.length()>=MAX_EXPRESSION&&!isOperator(key)){status="Maximum expression length";invalidate();return;}
        if(key.length()==1&&Character.isDigit(key.charAt(0))&&digits()>=MAX_DIGITS){status="Maximum 100 digits";invalidate();return;}
        if(key.equals(".")&&currentNumber().contains(".")){status="Decimal already added";invalidate();return;}
        if(isOperator(key)){
            if(expression.isEmpty()&&!key.equals("−"))return;
            if(!expression.isEmpty()&&isOperator(expression.substring(expression.length()-1)))expression=expression.substring(0,expression.length()-1)+key;
            else expression+=key;
        } else expression+=key;
        status="Expression";displayOffset=0;invalidate();
    }

    private void action(String name) {
        if(name.equals("clear")){expression="";status="Ready";displayOffset=0;}
        else if(name.equals("back")){deleteOne();}
        else if(name.equals("sign")){if(expression.startsWith("−"))expression=expression.substring(1);else expression="−"+expression;status="Expression";}
        else if(name.equals("equals")){
            try{String old=CalculatorEngine.trimOperators(expression);if(old.isEmpty())return;String result=CalculatorEngine.evaluate(old);expression=result;status=old+" =";activity.addHistory("Calculator",old+" = "+result);displayOffset=0;}
            catch(Exception ex){status="Invalid expression";}
        } else if(name.equals("clearHistory")){activity.clearHistory();historyOffset=0;}
        invalidate();
    }

    private void deleteOne(){if(!expression.isEmpty())expression=expression.substring(0,expression.length()-1);status=expression.isEmpty()?"Ready":"Expression";displayOffset=0;invalidate();}
    private void startDeleteRepeat(){stopDeleteRepeat();repeatDelete=new Runnable(){public void run(){if(pressed!=null&&!expression.isEmpty()){deleteOne();handler.postDelayed(this,45);}}};handler.postDelayed(repeatDelete,300);}
    private void stopDeleteRepeat(){if(repeatDelete!=null)handler.removeCallbacks(repeatDelete);repeatDelete=null;}
    private int digits(){int n=0;for(int i=0;i<expression.length();i++)if(Character.isDigit(expression.charAt(i)))n++;return n;}
    private String currentNumber(){int i=expression.length()-1;while(i>=0&&(Character.isDigit(expression.charAt(i))||expression.charAt(i)=='.'))i--;return expression.substring(i+1);}
    private boolean isOperator(String s){return s.equals("+")||s.equals("−")||s.equals("×")||s.equals("÷")||s.equals("^");}
    private Hit findHit(float x,float y){for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(x,y))return hits.get(i);return null;}

    private void text(Canvas c,String value,float x,float y,float size,int color,Paint.Align align,boolean bold){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);paint.setTextAlign(align);paint.setTypeface(android.graphics.Typeface.create("sans",bold?1:0));c.drawText(value,x,y,paint);}
    private void round(Canvas c,RectF r,float radius,int fill,int stroke){paint.setStyle(Paint.Style.FILL);paint.setColor(fill);c.drawRoundRect(r,radius,radius,paint);if(stroke!=Color.TRANSPARENT){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(stroke);c.drawRoundRect(r,radius,radius,paint);paint.setStyle(Paint.Style.FILL);}}
    private RectF inset(RectF r,float v){return new RectF(r.left+v,r.top+v,r.right-v,r.bottom-v);}
    private float dp(float v){return v*density;}
    private int bg(){return lightTheme?Color.rgb(240,248,252):Color.rgb(1,20,31);} private int header(){return lightTheme?Color.WHITE:Color.rgb(3,27,41);}
    private int card(){return lightTheme?Color.rgb(224,241,249):Color.rgb(5,47,68);} private int surface(){return lightTheme?Color.rgb(232,245,250):Color.rgb(7,43,61);}
    private int selected(){return lightTheme?Color.rgb(207,238,249):Color.rgb(8,59,82);} private int operator(){return Color.rgb(13,169,245);}
    private int text(){return lightTheme?Color.rgb(7,28,40):Color.rgb(244,250,255);} private int muted(){return lightTheme?Color.rgb(70,111,132):Color.rgb(126,177,201);}
    private int accent(){return Color.rgb(9,211,249);} private int border(){return lightTheme?Color.rgb(169,216,233):Color.rgb(13,85,112);}

    private static final class Hit { final RectF rect;final String type,value;Hit(RectF r,String t,String v){rect=new RectF(r);type=t;value=v;} }
}
