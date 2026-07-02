package com.fnmt.sample_dnie_app.utils.graphic;

import android.content.Context;
import android.graphics.Bitmap;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

public class CanvasView extends View {
    public enum TYPE { NONE, BITMAP, DRAW }
    public enum CENSORED { TRUE, FALSE, UNKWON}

    private final String TAG = CanvasView.class.getSimpleName();
    private Paint mPaint;
    private TYPE _type = TYPE.NONE;

    private Bitmap _bitmap = null;
    private CENSORED _censored = CENSORED.UNKWON;
    private String _age = null;

    public CanvasView(Context context) {
        this(context, null);
    }

    public CanvasView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CanvasView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, 0, 0);
        init();
    }

    private void init(){
        mPaint = new Paint();
        mPaint.setColor(Color.BLACK);
    }

    public void setType(TYPE type){
        _type = type;
    }

    public void setBitmap(Bitmap bitmap){
        _bitmap = bitmap;
    }

    public void setAge(String age){
        _age = age;
    }

    public void setCensored(CENSORED censored){
        _censored = censored;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            switch (_type){
                case BITMAP:
                    if(_bitmap!= null) {
                        float dx = 0;
                        float wScale = (float) getWidth() / (float) _bitmap.getWidth();
                        float hScale = (float) getHeight() / (float) _bitmap.getHeight();

                        float scale = hScale > wScale ? wScale : hScale;
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.setScale(scale, scale);
                        float imageWidth = scale * _bitmap.getWidth();
                        //center aligment
                        dx = (getWidth() - imageWidth) / 2;
                        matrix.postTranslate(dx, (getHeight() - (scale * _bitmap.getHeight())) / 2);
                        canvas.drawBitmap(_bitmap, matrix, mPaint);
                    }
                    break;
                case DRAW:
                    setBackgroundColor(Color.TRANSPARENT);
                    int middleX = getWidth() / 2;
                    int middleY = getHeight() / 2;
                    int radius = middleX > middleY ? middleY : middleX;
                    Rect bounds = new Rect();
                    if(_censored == CENSORED.UNKWON) {
                        String text = "¿+"+_age+"?";
                        mPaint.setColor(Color.BLUE);
                        mPaint.setTextSize((int)(radius*0.80));
                        mPaint.getTextBounds(text, 0, text.length(), bounds);
                        canvas.drawText(text, middleX - bounds.width()/2, middleY - bounds.height()/-2, mPaint);
                    }
                    else{
                        String text = "+" + _age;
                        int textSize = (int)(radius*0.80);
                        mPaint.setTextSize(textSize);
                        mPaint.getTextBounds(text, 0, text.length(), bounds);
                        int strokeWidth = (int)(radius*0.20);
                        mPaint.setStrokeWidth(strokeWidth/2);
                        if (_censored == CENSORED.FALSE) {
                            mPaint.setColor(Color.parseColor("#00ff80"));
                            canvas.drawCircle(middleX, middleY, radius, mPaint);
                            mPaint.setColor(Color.BLACK);
                            canvas.drawText(text, middleX - bounds.width()/2, middleY - bounds.height()/-2, mPaint);
                        } else {
                            mPaint.setColor(Color.RED);
                            canvas.drawCircle(middleX, middleY, radius, mPaint);
                            mPaint.setColor(Color.WHITE);
                            canvas.drawCircle(middleX, middleY, (radius) - strokeWidth, mPaint);
                            mPaint.setColor(Color.BLACK);
                            canvas.drawText(text, middleX - bounds.width()/2, middleY - bounds.height()/-2, mPaint);
                            mPaint.setColor(Color.RED);
                            canvas.save();
                            canvas.rotate(45, middleX, middleY);
                            canvas.drawLine(middleX, strokeWidth / 2, middleX, middleY, mPaint);
                            canvas.drawLine(middleX, middleY, middleX, getHeight() - strokeWidth / 2, mPaint);
                            canvas.restore();
                        }
                    }
                    break;
                }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
        super.onDraw(canvas);
    }
}
