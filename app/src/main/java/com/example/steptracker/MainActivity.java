package com.example.steptracker;

import java.util.Calendar;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // объявляем все элементы интерфейса чтобы потом к ним обращаться
    private TextView tvStepCount, tvStepsLabel;
    private ImageView btnSettings;
    private LineChart lineChart;
    private TabLayout tabLayoutFilters;

    // штуки для работы с физическим датчиком телефона
    private SensorManager sensorManager;
    private Sensor stepSensor;

    // переменные для правильного подсчета шагов
    private int totalSteps = 0;
    private int previousTotalSteps = 0;
    private int currentSteps = 0;
    private int stepsAtLastSync = 0;
    private int todayTotalFromDB = 0;

    // запоминаем текущую вкладку чтобы знать какой график обновлять
    private String currentFilter = "today";

    // флаг защиты от двойной отправки данных если идем слишком быстро
    private boolean isSyncing = false;

    // переменные для подключения к базе данных гугла
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private ArrayList<String> xLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // связываем переменные с реальными вьюшками из xml файла
        tvStepCount = findViewById(R.id.tvStepCount);
        tvStepsLabel = findViewById(R.id.tvStepsLabel);
        btnSettings = findViewById(R.id.btnSettings);
        lineChart = findViewById(R.id.lineChart);
        tabLayoutFilters = findViewById(R.id.tabLayoutFilters);

        // инициализируем фаербейз чтобы тянуть оттуда инфу
        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // запрашиваем системные права на активность для новых андроидов
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 1);
            }
        }

        // грузим локальный кэш чтобы не потерять шаги при выходе
        loadLocalData();

        // находим системный датчик шагов внутри телефона
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        }

        // вешаем клик на шестеренку для перехода в настройки
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        // рисуем пустой график и сразу грузим в него данные за сегодня
        setupLineChart();
        loadChartData(currentFilter);

        // слушаем переключения вкладок неделя месяц сегодня
        tabLayoutFilters.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // меняем фильтр и сразу перерисовываем график
                switch (tab.getPosition()) {
                    case 0: currentFilter = "today"; break;
                    case 1: currentFilter = "week"; break;
                    case 2: currentFilter = "month"; break;
                }
                loadChartData(currentFilter);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // как только открыли окно начинаем слушать датчик
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI);
        }
        // при возврате в приложение обновляем график актуальными данными из бд
        loadChartData(currentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // перед сворачиванием обязательно скидываем остатки в базу
        syncWithFirebase();
    }

    // этот метод вызывается железом каждый раз когда мы делаем шаг
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            totalSteps = (int) event.values[0];

            // если мы только что вошли в акк и кэш пустой берем текущее значение датчика
            if (previousTotalSteps == 0) {
                previousTotalSteps = totalSteps;
            }

            // защита от перезагрузки телефона когда датчик скидывается в ноль
            if (totalSteps < previousTotalSteps) {
                previousTotalSteps = totalSteps;
            }

            // считаем разницу с прошлого тика и плюсуем к нашему счетчику
            int stepsDelta = totalSteps - previousTotalSteps;
            previousTotalSteps = totalSteps;
            currentSteps += stepsDelta;

            // сразу пишем в локальную память чтобы не потерять если сядет батарея
            getSharedPreferences("StepPrefs", MODE_PRIVATE).edit()
                    .putInt("previousTotalSteps", previousTotalSteps)
                    .putInt("currentSteps", currentSteps)
                    .apply();

            // обновляем большую цифру на экране добавляя новые несинхронизированные шаги
            int unsyncedSteps = currentSteps - stepsAtLastSync;
            if (unsyncedSteps < 0) unsyncedSteps = 0;
            tvStepCount.setText(String.valueOf(todayTotalFromDB + unsyncedSteps));

            // если накопилось 15 шагов скидываем их в бд прямо на ходу
            // это обновит график без необходимости закрывать приложение
            if (unsyncedSteps >= 15) {
                syncWithFirebase();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // метод для загрузки кэша из внутренней памяти телефона
    private void loadLocalData() {
        previousTotalSteps = getSharedPreferences("StepPrefs", MODE_PRIVATE).getInt("previousTotalSteps", 0);
        stepsAtLastSync = getSharedPreferences("StepPrefs", MODE_PRIVATE).getInt("stepsAtLastSync", 0);
        currentSteps = getSharedPreferences("StepPrefs", MODE_PRIVATE).getInt("currentSteps", 0);
    }

    // главная функция синхронизации с облаком
    private void syncWithFirebase() {
        // проверяем что есть шаги для отправки и мы не в процессе другой синхронизации
        if (mAuth.getCurrentUser() != null && currentSteps > stepsAtLastSync && !isSyncing) {
            isSyncing = true;
            int stepsToSave = currentSteps - stepsAtLastSync;

            String userId = mAuth.getCurrentUser().getUid();
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            String hour = new SimpleDateFormat("HH", Locale.getDefault()).format(new Date());

            // строим путь в базе данных прям до текущего часа
            DatabaseReference hourRef = mDatabase.child("Users").child(userId).child("steps").child(date).child(hour);

            hourRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    // читаем шаги за этот час из базы и плюсуем наши новые
                    int existingSteps = snapshot.exists() ? snapshot.getValue(Integer.class) : 0;
                    hourRef.setValue(existingSteps + stepsToSave);

                    // обновляем метку синхронизации чтобы не отправить их дважды
                    stepsAtLastSync = currentSteps;
                    String timeStr = new SimpleDateFormat("dd MMM yyyy, HH:mm:ss", new Locale("ru")).format(new Date());

                    // запоминаем время последней отправки для экрана настроек
                    getSharedPreferences("StepPrefs", MODE_PRIVATE).edit()
                            .putInt("stepsAtLastSync", stepsAtLastSync)
                            .putInt("currentSteps", currentSteps)
                            .putString("lastSync", timeStr)
                            .apply();

                    isSyncing = false;

                    // запрашиваем перерисовку графика после успешного сохранения в бд
                    loadChartData(currentFilter);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    isSyncing = false;
                }
            });
        }
    }

    // убираем всю лишнюю грязь с графика типа сеток и легенд
    private void setupLineChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(false);
        lineChart.setDrawGridBackground(false);
        lineChart.setDrawBorders(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(false);

        // прикручиваем нашу кастомную синюю подсказку при клике на график
        CustomMarkerView mv = new CustomMarkerView(this, R.layout.custom_marker_view);
        lineChart.setMarker(mv);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setTextColor(Color.parseColor("#A0B0C0"));
        xAxis.setGranularity(1f);

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#2A3952"));
        leftAxis.setDrawAxisLine(false);
        leftAxis.setTextColor(Color.parseColor("#A0B0C0"));
        leftAxis.setAxisMinimum(0f);

        lineChart.getAxisRight().setEnabled(false);
    }

    // скачиваем историю из базы и решаем как ее отрисовать
    private void loadChartData(String filter) {
        if (mAuth.getCurrentUser() == null) return;

        String userId = mAuth.getCurrentUser().getUid();
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String currentMonthPrefix = new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(new Date());

        // откидываем ровно шесть дней назад для фильтра недели
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        String sevenDaysAgo = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.getTime());

        mDatabase.child("Users").child(userId).child("steps").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                ArrayList<Entry> entries = new ArrayList<>();
                xLabels.clear();

                // логика если смотрим стату только за сегодня
                if (filter.equals("today")) {
                    todayTotalFromDB = 0;
                    tvStepsLabel.setText("шагов сегодня");

                    DataSnapshot todaySnapshot = snapshot.child(todayDate);
                    // проходимся циклом по всем 24 часам
                    for (int i = 0; i < 24; i++) {
                        String hourKey = String.format(Locale.getDefault(), "%02d", i);
                        int steps = todaySnapshot.hasChild(hourKey) ? todaySnapshot.child(hourKey).getValue(Integer.class) : 0;
                        entries.add(new Entry(i, steps));
                        xLabels.add(hourKey + ":00");

                        // собираем общую сумму за день
                        todayTotalFromDB += steps;
                    }

                    // выводим сумму на экран
                    int unsyncedSteps = currentSteps - stepsAtLastSync;
                    if (unsyncedSteps < 0) unsyncedSteps = 0;
                    tvStepCount.setText(String.valueOf(todayTotalFromDB + unsyncedSteps));

                } else {
                    // логика если смотрим за неделю или месяц
                    int totalStepsForPeriod = 0;
                    int i = 0;
                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                    SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM", new Locale("ru"));

                    for (DataSnapshot dateSnapshot : snapshot.getChildren()) {
                        String dateKey = dateSnapshot.getKey();
                        if (dateKey == null) continue;

                        boolean includeDate = false;

                        // проверяем попадает ли дата в выбранный период
                        if (filter.equals("week")) {
                            if (dateKey.compareTo(sevenDaysAgo) >= 0 && dateKey.compareTo(todayDate) <= 0) includeDate = true;
                        } else if (filter.equals("month")) {
                            if (dateKey.startsWith(currentMonthPrefix)) includeDate = true;
                        }

                        // если дата подходит то плюсуем все часы внутри этого дня
                        if (includeDate) {
                            int dailyTotal = 0;
                            for (DataSnapshot hourSnapshot : dateSnapshot.getChildren()) {
                                Integer val = hourSnapshot.getValue(Integer.class);
                                if (val != null) dailyTotal += val;
                            }
                            totalStepsForPeriod += dailyTotal;
                            entries.add(new Entry(i, dailyTotal));

                            // пытаемся красиво отформатировать дату для подписи снизу
                            try {
                                Date parsedDate = inputFormat.parse(dateKey);
                                xLabels.add(outputFormat.format(parsedDate));
                            } catch (Exception e) {
                                xLabels.add(dateKey.substring(5));
                            }
                            i++;
                        }
                    }

                    tvStepCount.setText(String.valueOf(totalStepsForPeriod));
                    tvStepsLabel.setText(filter.equals("week") ? "шагов за неделю" : "шагов за месяц");
                }

                // отдаем собранные точки на отрисовку
                renderLineChart(entries);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // финальная отрисовка графика
    private void renderLineChart(ArrayList<Entry> entries) {
        if (!entries.isEmpty()) {
            LineDataSet dataSet = new LineDataSet(entries, "Шаги");
            dataSet.setColor(Color.parseColor("#f7b733"));
            dataSet.setLineWidth(3f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);

            // скругляем углы чтобы график выглядел плавно как волна
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

            // заливаем пустоту под линией желтым градиентом
            dataSet.setDrawFilled(true);
            Drawable drawable = ContextCompat.getDrawable(this, R.drawable.fade_yellow);
            dataSet.setFillDrawable(drawable);

            LineData lineData = new LineData(dataSet);
            lineChart.setData(lineData);
            lineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(xLabels));

            // масштабируем ось икс чтобы 24 часа не слиплись в одну кашу
            if (xLabels.size() == 24) {
                lineChart.setVisibleXRangeMaximum(6);
                lineChart.moveViewToX(24);
            } else {
                lineChart.fitScreen();
            }

            // включаем красивую анимацию при загрузке
            lineChart.animateY(1000);
            lineChart.invalidate();
        }
    }
}