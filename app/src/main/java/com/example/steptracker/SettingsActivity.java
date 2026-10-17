package com.example.steptracker;

import com.google.firebase.database.FirebaseDatabase;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import com.google.firebase.auth.FirebaseAuth;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        TextView tvCurrentDate = findViewById(R.id.tvCurrentDate);
        TextView tvLastSync = findViewById(R.id.tvLastSync);
        TextView tvDatabaseUrl = findViewById(R.id.tvDatabaseUrl);
        Button btnLogout = findViewById(R.id.btnLogoutFromSettings);

        // Устанавливаем текущую дату
        String currentDate = new SimpleDateFormat("dd MMMM yyyy", new Locale("ru")).format(new Date());
        tvCurrentDate.setText(currentDate);

        // Достаем время последней синхронизации
        String lastSync = getSharedPreferences("StepPrefs", MODE_PRIVATE).getString("lastSync", "Еще не было");
        tvLastSync.setText(lastSync);

        //получаем ссылку на бд
        String actualDbUrl = FirebaseDatabase.getInstance().getReference().toString();
        tvDatabaseUrl.setText(actualDbUrl);

        // Кнопка выхода
        btnLogout.setOnClickListener(v -> {
            //очищаем локальные данные шагомера
            getSharedPreferences("StepPrefs", MODE_PRIVATE).edit().clear().apply();

            //выходим из Firebase
            FirebaseAuth.getInstance().signOut();

            //возвращаемся на экран логина
            Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}