package com.autopostpro.app;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.*;
import androidx.recyclerview.widget.*;
import androidx.work.*;

import com.facebook.*;
import com.facebook.login.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    static final int REQ_V = 100, REQ_T = 200;

    // Views
    MaterialCardView cardLogin, 
