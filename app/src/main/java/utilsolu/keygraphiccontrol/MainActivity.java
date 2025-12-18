package utilsolu.keygraphiccontrol;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.BatteryManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.core.splashscreen.SplashScreen;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final long LONG_PRESS_DURATION = 3000;

    // --- CONFIGURACIÓN DE RED ---
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 8090;
    private Socket clientSocket;
    private PrintWriter printwriter;
    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isConnected = false;

    // --- VARIABLES DE RECONEXIÓN AUTOMÁTICA ---
    private Handler reconnectHandler = new Handler();
    private static final int RECONNECT_INTERVAL_MS = 5000;
    private volatile boolean reconnectScheduled = false;

    private Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected) {
                Log.d(TAG, "Reintento de conexión programado...");
                connectToServer();
                reconnectHandler.postDelayed(this, RECONNECT_INTERVAL_MS);
            } else {
                reconnectScheduled = false;
            }
        }
    };

    // --- VARIABLES DE ESTADO Y COMPONENTES UI ---
    private int currentMode = 1;
    private boolean isZoomActive = false;
    private BroadcastReceiver powerConnectionReceiver;

    private View indicatorLeft;
    private View indicatorRight;

    // Componentes para el Joystick
    private FrameLayout joystickContainer;
    private ImageView joystickNub;
    private int joystickCenterX;
    private int joystickCenterY;
    private int joystickRadius;
    private static final int JOYSTICK_MOVEMENT_THRESHOLD = 20;

    // Componentes de la interfaz
    private Handler handler = new Handler();
    private Runnable longPressRunnable;
    private List<View> modeButtons;

    // -------------------------------------------------------------------------
    // --- CICLO DE VIDA DE LA ACTIVIDAD ---
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);

        // Habilitar Modo Inmersivo (Pantalla Completa)
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);



        // Habilitar Modo Inmersivo (Pantalla Completa)
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentMode == 1) {
                    isZoomActive = true;
                    Toast.makeText(MainActivity.this, "¡Modo ZOOM Activado!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Función Secreta: ZOOM ACTIVADO");
                }
            }
        };

        indicatorLeft = findViewById(R.id.indicator_left);
        indicatorRight = findViewById(R.id.indicator_right);

        // Inicialización de UI
        initializeTopControls();
        initializeJoystickControl();
        initializeButtonColumns();
        initializeReconnectButton();

        // Al inicio, forzamos el estado de interfaz: Desconectado (ROJO a la IZQUIERDA)
        updateConnectionStatusUI(false);

        // --- INICIALIZACIÓN CRÍTICA ---
        initializeConnectionReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeConnection();

        // Detener la escucha de eventos USB/Cargador
        unregisterReceiver(powerConnectionReceiver);

        // Detener cualquier reintento pendiente
        reconnectHandler.removeCallbacks(reconnectRunnable);

        networkExecutor.shutdown();
    }

    // Método para mantener el Modo Inmersivo
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // -------------------------------------------------------------------------
    // --- LÓGICA DE RECONEXIÓN AUTOMÁTICA Y ESTADO ---
    // -------------------------------------------------------------------------

    private void closeAndReconnect() {
        if (isConnected) {
            closeConnection();
            scheduleReconnectAttempt(2000); // Intenta de nuevo en 2 segundos
        }
    }

    // Programa el reintento si aún no hay uno activo.
    private void scheduleReconnectAttempt(long delay) {
        if (!reconnectScheduled) {
            reconnectScheduled = true;
            reconnectHandler.postDelayed(reconnectRunnable, delay);
        }
    }

    // Método para actualizar la interfaz de los indicadores (cambia el color de fondo)
    private void updateConnectionStatusUI(boolean connected) {
        runOnUiThread(() -> {
            if (connected) {
                // CONECTADO: DERECHA (GREEN) activo, IZQUIERDA (ROJO) apagado
                indicatorRight.setBackgroundResource(R.drawable.circle_status_green);
                indicatorLeft.setBackgroundResource(R.drawable.circle_status_gray);
            } else {
                // DESCONECTADO: IZQUIERDA (RED) activo, DERECHA (GREEN) apagado
                indicatorLeft.setBackgroundResource(R.drawable.circle_status_red);
                indicatorRight.setBackgroundResource(R.drawable.circle_status_gray);
            }
        });
    }

    // -------------------------------------------------------------------------
    // --- LÓGICA DEL BOTÓN DE RECONEXIÓN MANUAL (RELOAD) ---
    // -------------------------------------------------------------------------

    private void initializeReconnectButton() {
        LinearLayout reconnectButton = findViewById(R.id.card_reconnect_button);

        reconnectButton.setOnClickListener(v -> {
            Log.d(TAG, "Botón de reconexión manual presionado. Forzando intento de conexión.");

            // 🛑 CLAVE 1: Forzar el cierre de cualquier conexión zombie primero.
            // Esto pondrá isConnected=false y la UI en ROJO.
            closeConnection();

            // Detener cualquier reintento programado
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectScheduled = false;

            // 🛑 CLAVE 2: Llamar a connectToServer(). Como isConnected ahora es false,
            // intentará abrir un nuevo socket. Si el servidor PC está apagado,
            // el intento fallará y la UI se mantendrá en ROJO.
            connectToServer();

            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Intentando Reconexión...", Toast.LENGTH_SHORT).show());
        });
    }

    // -------------------------------------------------------------------------
    // --- LÓGICA DE CONEXIÓN Y DESCONEXIÓN ---
    // -------------------------------------------------------------------------

    private void initializeConnectionReceiver() {
        powerConnectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                    Log.d(TAG, "EVENTO: Cable USB conectado. Intentando conectar a la PC...");
                    connectToServer();
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                    Log.d(TAG, "EVENTO: Cable USB desconectado. Cerrando conexión.");
                    closeConnection();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Conexión con PC perdida.", Toast.LENGTH_SHORT).show());
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerConnectionReceiver, filter);

        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        boolean usbConnected = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;

        if (usbConnected) {
            Log.d(TAG, "ESTADO INICIAL: Cable USB ya estaba conectado. Intentando conexión.");
            connectToServer();
        }
    }

    // --- MÉTODO DE CONEXIÓN ---
    private void connectToServer() {
        // Solo bloqueamos si ya estamos conectados Y no hay reconexión pendiente (previniendo bucles)
        if (isConnected && !reconnectScheduled) return;

        // Si estamos aquí, es porque isConnected es false (o estamos en un bucle de reintento).
        updateConnectionStatusUI(false);

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. LIMPIEZA FORZADA DE SOCKETS ANTERIORES
                    if (clientSocket != null) {
                        try { clientSocket.close(); } catch (IOException ignore) {}
                        clientSocket = null;
                    }

                    // 2. Intenta la nueva conexión. Si el servidor PC está apagado, aquí falla.
                    clientSocket = new Socket(SERVER_IP, SERVER_PORT);
                    printwriter = new PrintWriter(clientSocket.getOutputStream(), true);
                    isConnected = true;

                    // 🚀 ÉXITO: Cambiar indicador a VERDE y CANCELAR cualquier reintento pendiente
                    reconnectHandler.removeCallbacks(reconnectRunnable);
                    reconnectScheduled = false;
                    updateConnectionStatusUI(true);

                    Log.i(TAG, "Conexión TCP establecida y estable.");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Control Remoto Conectado.", Toast.LENGTH_SHORT).show());

                } catch (Exception e) {
                    Log.e(TAG, "Fallo al conectar: " + e.getMessage());
                    isConnected = false;
                    clientSocket = null;
                    printwriter = null;

                    // 🛑 FALLO: Cambiar indicador a ROJO y programar un reintento
                    updateConnectionStatusUI(false);
                    scheduleReconnectAttempt(RECONNECT_INTERVAL_MS);
                }
            }
        });
    }

    // --- MÉTODO DE CIERRE ---
    private void closeConnection() {
        if (!isConnected) return;

        isConnected = false;

        // 🛑 CIERRE: Cambiar indicador a ROJO
        updateConnectionStatusUI(false);

        if (printwriter != null) {
            printwriter.close();
        }
        if (clientSocket != null) {
            try {
                // Cerrar el socket para liberar el puerto
                clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar socket: " + e.getMessage());
            }
        }
        clientSocket = null;
        printwriter = null;
    }

    // --- MÉTODO DE ENVÍO CRÍTICO (Detecta Desconexión Inesperada) ---
    private void sendCommandToPC(String commandType, String value) {
        if (!isConnected) {
            Log.w(TAG, "Intento de envío sin conexión activa. Intentando conectar.");
            // Si no estaba conectado, forzamos un intento de conexión (Reload implícito)
            connectToServer();
            return;
        }

        final String commandString = commandType + "," + value;
        Log.i(TAG, "COMANDO ENVIADO: " + commandString);

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (printwriter != null) {
                        printwriter.println(commandString);
                    } else {
                        // Si printwriter es nulo pero isConnected=true (estado zombie)
                        Log.e(TAG, "Error: Printwriter es nulo. Forzando cierre y reconexión.");
                        closeAndReconnect();
                    }
                } catch (Exception e) {
                    // 🚨 CLAVE: Detectamos fallo en la red (Servidor PC cerrado inesperadamente)
                    // Esto cambia el indicador a ROJO y programa el reintento automático.
                    Log.e(TAG, "Fallo al enviar el comando. Servidor PC cerrado: " + e.getMessage());
                    closeAndReconnect();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // --- LÓGICA DE JOYSTICK Y CONTROL ---
    // -------------------------------------------------------------------------

    private void initializeJoystickControl() {
        joystickContainer = findViewById(R.id.joystick_container);
        joystickNub = findViewById(R.id.joystick_nub);

        joystickContainer.post(() -> {
            int containerWidth = joystickContainer.getWidth();
            int containerHeight = joystickContainer.getHeight();
            int nubWidth = joystickNub.getWidth();
            int nubHeight = joystickNub.getHeight();

            joystickCenterX = containerWidth / 2;
            joystickCenterY = containerHeight / 2;

            joystickRadius = (Math.min(containerWidth, containerHeight) - Math.min(nubWidth, nubHeight)) / 2;
        });

        joystickContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isConnected) {
                    Toast.makeText(MainActivity.this, "Conecte el cable USB primero.", Toast.LENGTH_SHORT).show();
                    return false;
                }

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        moveJoystick(event.getX(), event.getY());
                        float dx = event.getX() - joystickCenterX;
                        float dy = event.getY() - joystickCenterY;

                        if (Math.sqrt(dx * dx + dy * dy) > JOYSTICK_MOVEMENT_THRESHOLD * 2) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPressRunnable);
                        resetJoystick();

                        if (isZoomActive) {
                            Log.d(TAG, "Modo ZOOM Desactivado.");
                        }
                        isZoomActive = false;
                        return true;
                }
                return true;
            }
        });
    }

    private void moveJoystick(float touchX, float touchY) {
        float dx = touchX - joystickCenterX;
        float dy = touchY - joystickCenterY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance > joystickRadius) {
            float ratio = joystickRadius / (float) distance;
            dx *= ratio;
            dy *= ratio;
        }

        joystickNub.setTranslationX(dx);
        joystickNub.setTranslationY(dy);

        detectAndSendDirection(dx, dy);
    }

    private void resetJoystick() {
        joystickNub.setTranslationX(0);
        joystickNub.setTranslationY(0);
        sendCommandToPC("DIRECTION", "STOP");
    }

    private void detectAndSendDirection(float dx, float dy) {

        if (currentMode == 1) {

            if (isZoomActive) {
                // Lógica de ZOOM
                if (dx > 0 && dx > JOYSTICK_MOVEMENT_THRESHOLD) {
                    sendCommandToPC("ZOOM", "IN");
                } else if (dx < 0 && Math.abs(dx) > JOYSTICK_MOVEMENT_THRESHOLD) {
                    sendCommandToPC("ZOOM", "OUT");
                }
            } else {
                // Lógica de DESPLAZAMIENTO
                String direction = "NONE";

                if (Math.abs(dx) > Math.abs(dy)) {
                    // Horizontal
                    if (dx > JOYSTICK_MOVEMENT_THRESHOLD) direction = "RIGHT";
                    else if (dx < -JOYSTICK_MOVEMENT_THRESHOLD) direction = "LEFT";
                } else {
                    // Vertical
                    if (dy > JOYSTICK_MOVEMENT_THRESHOLD) direction = "DOWN";
                    else if (dy < -JOYSTICK_MOVEMENT_THRESHOLD) direction = "UP";
                }

                if (!direction.equals("NONE")) {
                    sendCommandToPC("DIRECTION", direction);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // --- LÓGICA DE BOTONES Y MODOS ---
    // -------------------------------------------------------------------------

    private void initializeTopControls() {
        modeButtons = Arrays.asList(
                findViewById(R.id.modo_boton_1),
                findViewById(R.id.modo_boton_2),
                findViewById(R.id.modo_boton_3)
        );

        for (View button : modeButtons) {
            button.setOnClickListener(this::handleModeSelection);
        }

        if (!modeButtons.isEmpty()) {
            handleModeSelection(modeButtons.get(0));
        }
    }

    private void handleModeSelection(View selectedView) {
        for (View button : modeButtons) {
            int modeIndex = modeButtons.indexOf(button) + 1;
            if (button.getId() == selectedView.getId()) {
                button.setBackgroundResource(R.drawable.circle_button_on);
                currentMode = modeIndex;
                Log.d(TAG, "Modo " + currentMode + " ACTIVADO");
            } else {
                button.setBackgroundResource(R.drawable.circle_button_off);
            }
        }
    }

    private void initializeButtonColumns() {
        LinearLayout columnLeft = findViewById(R.id.column_left);
        LinearLayout columnRight = findViewById(R.id.column_right);

        setupColumnListeners(columnLeft, "IZQUIERDA");
        setupColumnListeners(columnRight, "DERECHA");
    }

    private void setupColumnListeners(LinearLayout container, String side) {
        if (container != null) {
            for (int i = 0; i < container.getChildCount(); i++) {
                final View buttonView = container.getChildAt(i);
                final int buttonIndex = i + 1;

                if (buttonView instanceof androidx.cardview.widget.CardView) {
                    buttonView.setOnClickListener(v -> {
                        String command = "BTN_" + side.toUpperCase() + "_" + buttonIndex;
                        sendCommandToPC("BUTTON", command);
                    });
                }
            }
        }
    }
}