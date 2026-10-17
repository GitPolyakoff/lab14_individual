package com.example.steptracker;

import android.content.Context;
import android.widget.TextView;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

public class CustomMarkerView extends MarkerView {

    private TextView tvMarkerSteps;

    public CustomMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);
        tvMarkerSteps = findViewById(R.id.tvMarkerSteps);
    }

    // Этот метод вызывается каждый раз при клике на столбец графика
    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        int steps = (int) e.getY();
        tvMarkerSteps.setText(steps + " шагов");
        super.refreshContent(e, highlight);
    }

    // Центрируем подсказку над столбцом
    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 20);
    }
}