package spinninghead.thermalcamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.Toast;

import com.yahoo.mobile.client.android.util.rangeseekbar.RangeSeekBar;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Stack;

import static android.widget.Toast.makeText;

/**
 *  Activity to be used with an ESP32 running a Webserver pushing UDP packets of thermal sensor data
 */
public class ThermalCameraActivity extends AppCompatActivity {

    //Message ID for Update UI handler
    private static int UPDATE_UI = 1111;

    public HandlerThread mHandlerThread;

    Thread clientListenThread = null;
    ClientListen clientListen;
    int port = 5037;

    float maxTemp = -1000;
    float minTemp = 1000;
    float minF = 0;
    float maxF = 0;

    ImageView imgThermalView;
    RangeSeekBar seekBar;

    Stack  <float[]> frameBuffer = new Stack();
    static int BUFFER_MAX_SIZE = 100;

    int colorMap[];


    /**
     * Handler used to update UI.
     */
    static class UiUpdateHandler extends Handler {

        //WeakReference back to activity used to allow activity garbage collection as needed
        public WeakReference<ThermalCameraActivity> activity = null;

        public UiUpdateHandler(ThermalCameraActivity activity, Looper looper) {
            super(looper);
            this.activity = new WeakReference<ThermalCameraActivity>(activity);
        }

        @Override
        public void handleMessage(Message m) {
            if (m.what == UPDATE_UI ) {
                try {
                    if (activity != null) {
                        activity.get().updateUI();
                    }
                } catch (NullPointerException ex) {

                }
            }
        }

        /**
         * Clears the weak reference to the activity
         */
        protected void clearActivityReference() {
            if (activity!=null) {
                activity.clear();
                activity = null;
            }
        }
    }

    protected UiUpdateHandler uiUpdateHandler;

    /**
     * Creates colorMap that will be used to represent temperature as colors. This could be a static array of values, but it is easier to just write the code to produce the array.
     * Executed once per life of activity
     */
    protected void createColorMap() {

        colorMap = new int[1530];

        int blue = 0;
        int green = 0;
        int red = 0;
        int alpha = 0;

        int i = 0;

        for (blue = 0; blue < 255; blue++) {
            red++;
            if (alpha<255) {
                alpha++;
            }
            colorMap[i] = Color.argb(alpha, red, green, blue);
            i++;
        }

        for (red = 255; red > 0; red--) {
            colorMap[i] = Color.argb(255, red, green, blue);
            i++;
        }

        for (green = 0; green < 255; green++) {
            colorMap[i] = Color.argb(255, red, green, blue);
            i++;
        }

        for (blue = 255; blue > 0; blue--) {
            colorMap[i] = Color.argb(255, red, green, blue);
            i++;
        }

        for (red = 0; red < 255; red++) {
            colorMap[i] = Color.argb(255, red, green, blue);
            i++;
        }

        for (green = 255; green > 0; green--) {
            colorMap[i] = Color.argb(255, red, green, blue);
            i++;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createColorMap();

        Button btnStart = (Button) findViewById(R.id.start);
        Button btnStop = (Button) findViewById(R.id.stop);
        imgThermalView = (ImageView) findViewById(R.id.viewThermalImage);
        seekBar = (RangeSeekBar) findViewById(R.id.rangeSeekbar);
        NumberPicker numLow = (NumberPicker) findViewById(R.id.npkrLow);
        NumberPicker numHigh = (NumberPicker) findViewById(R.id.npkrHigh);
        ImageView imgScale = (ImageView) findViewById(R.id.imgScale);

        numLow.setMinValue(0);
        numLow.setMaxValue(340);
        numLow.setValue(0);

        numHigh.setMinValue(0);
        numHigh.setMaxValue(340);
        numHigh.setValue(340);

        seekBar.setNotifyWhileDragging(true);

        seekBar.setRangeValues(10, 40);

//        seekBar.setOnRangeSeekBarChangeListener(new RangeSeekBar.OnRangeSeekBarChangeListener() {
//            @Override
//            public void onRangeSeekBarValuesChanged(RangeSeekBar bar, Object minValue, Object maxValue) {
//
//                int minRangeValue = -40;
//                int maxRangeValue = 400;
//
//                int minInt = minRangeValue;
//                int maxInt = maxRangeValue;
//
//                if (minInt > -30) {
//                    minRangeValue = minInt - 10;
//                }
//
//                if (maxInt <390) {
//                    maxRangeValue = maxInt + 10;
//                }
//
//                seekBar.setRangeValues(minRangeValue, maxRangeValue);
//            }
//        });

        imgThermalView.setBackgroundColor(colorMap[0]);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startCamera();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopCamera();
            }
        });

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = null;
        bmp = Bitmap.createBitmap(640, 480, conf); // this creates a MUTABLE bitmap
        imgThermalView.setImageBitmap(bmp);

        mHandlerThread = new HandlerThread("SensorThread");
        mHandlerThread.setPriority(10);
        mHandlerThread.start();

    }

    protected void createTestData() {

        float testData = 0.0f;

        for (int i = 0; i < 10; i++) {
            float[] testPixels = new float[768];
            for (int ii = 0; ii < 768; ii++) {
                testPixels[ii] = testData++;
            }

            addFrame(testPixels);
        }


    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    protected void startCamera() {
        clientListen = new ClientListen();
        clientListenThread = new Thread(clientListen);
        clientListenThread.start();

        //createTestData();

        uiUpdateHandler = new UiUpdateHandler(this, mHandlerThread.getLooper());

        uiUpdateHandler.sendEmptyMessageDelayed(UPDATE_UI, 1000);

    }

    protected void stopCamera() {
        if (clientListen != null) {
            clientListen.run = false;
        }

        if (uiUpdateHandler!=null) {
            uiUpdateHandler.removeCallbacksAndMessages(UPDATE_UI);
            uiUpdateHandler.clearActivityReference();
            uiUpdateHandler = null;
        }
    }

    public void showToast(final String toast)
    {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(ThermalCameraActivity.this, toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    protected void addFrame(float[] frame) {

        frameBuffer.push(frame);

        if (frameBuffer.size() > BUFFER_MAX_SIZE) {
            frameBuffer.pop();
        }
    }

    protected int[][] createInterpolatedFrame(float[] frame) {

        int[][] pixels = new int[63][47];

        int y= 0;
        int x=0;
        int i = 0;

        while (y<47) {
            pixels[x][y] = getColorIndex(frame[i]);
            x++;

            while (x<62) {
                pixels[x][y] = (getColorIndex(frame[i]) + getColorIndex(frame[i+1]))/2;
                x++;
                pixels[x][y] = getColorIndex(frame[i+1]);
                x++;
                i++;
            }
            i=i+1;
            y=y+2;
            x=0;
        }

        y=1;
        x=0;

        float sum = 0;

        while (y<47) {
            while (x<63) {
                if (x % 2 == 0) {
                    pixels[x][y] = (pixels[x][y - 1] + pixels[x][y + 1]) / 2;
                } else {
                    pixels [x][y] = (pixels[x-1][y-1] + pixels[x+1][y-1] + pixels[x+1][y-1] + pixels[x-1][y+1])/4;
                }
                x++;
            }
            y=y+2;
            x=0;
        }

        return pixels;
    }

    protected int getColorIndex(float tempC) {

        int colorIndex = 0;

        int min = (int) seekBar.getSelectedMinValue();
        int max = (int) seekBar.getSelectedMaxValue();


        if (tempC < min) {
            colorIndex = 0;
        } else if (tempC > max) {
            colorIndex = 1529;
        } else {

            float tmp = tempC - min;
            colorIndex = Math.round((tmp/(max-min)) * 1529);

        }

        return colorIndex;

    }

    protected void createSciFiBitmap() {

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = Bitmap.createBitmap(384, 288, conf); // this creates a MUTABLE bitmap
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(5f);

        int colorInt = 0;

        int x = 0;
        int y = 288;
        int i = 0;

        int colorIndex = 0;

        if (frameBuffer.size() > 0) {
            float[] pixels = frameBuffer.pop();

            while  (y>0) {

                while (x<384) {


                    colorIndex = getColorIndex(pixels[i]);
                    p.setColor(colorMap[colorIndex]);
                    p.setAlpha(255);

                    canvas.drawRect(x + 4, canvas.getHeight() - y + 4, x + 8, (canvas.getHeight() - y) + 8, p);
                    p.setAlpha(127);

                    //left
                    canvas.drawRect(x , canvas.getHeight() - y , x + 4, (canvas.getHeight() - y) + 4, p);
                    canvas.drawRect(x , canvas.getHeight() - y + 4, x + 4, (canvas.getHeight() - y) + 8, p);
                    canvas.drawRect(x , canvas.getHeight() - y + 8, x + 4, (canvas.getHeight() - y) + 12, p);

                    //middle
                    canvas.drawRect(x + 4, canvas.getHeight() - y , x + 8, (canvas.getHeight() - y) + 4, p);
                    canvas.drawRect(x + 4, canvas.getHeight() - y + 8, x + 8, (canvas.getHeight() - y) + 12, p);

                    //right
                    canvas.drawRect(x + 8 , canvas.getHeight() - y , x + 12, (canvas.getHeight() - y) + 4, p);
                    canvas.drawRect(x + 8 , canvas.getHeight() - y + 4, x + 12, (canvas.getHeight() - y) + 8, p);
                    canvas.drawRect(x + 8 , canvas.getHeight() - y + 8, x + 12, (canvas.getHeight() - y) + 12, p);



                    x=x+12;
                    i=i+1;

                }

                x=0;

                y=y-12;
            }
            imgThermalView.setImageBitmap(bmp);
        }
    }

    protected void createInterpolatedBitmap() {

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = Bitmap.createBitmap(256, 192, conf); // this creates a MUTABLE bitmap
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(5f);

        int colorInt = 0;

        int x = 0;
        int y = 192;
        int i = 0;

        int colorIndex = 0;

        if (frameBuffer.size() > 0) {
            float[] pixels = frameBuffer.pop();

            while  (y>0) {

                while (x<256) {


                    colorIndex = getColorIndex(pixels[i]);
                    p.setColor(colorMap[colorIndex]);
                    p.setAlpha(255);

                    canvas.drawRect(x + 4, canvas.getHeight() - y + 4, x + 8, (canvas.getHeight() - y) + 8, p);


                        //left
                        canvas.drawRect(x, canvas.getHeight() - y, x + 4, (canvas.getHeight() - y) + 4, p);
                        canvas.drawRect(x, canvas.getHeight() - y + 4, x + 4, (canvas.getHeight() - y) + 8, p);
                        canvas.drawRect(x, canvas.getHeight() - y + 8, x + 4, (canvas.getHeight() - y) + 12, p);



                    //middle
                    canvas.drawRect(x + 4, canvas.getHeight() - y , x + 8, (canvas.getHeight() - y) + 4, p);
                    canvas.drawRect(x + 4, canvas.getHeight() - y + 8, x + 8, (canvas.getHeight() - y) + 12, p);

                    //right
                    canvas.drawRect(x + 8 , canvas.getHeight() - y , x + 12, (canvas.getHeight() - y) + 4, p);
                    canvas.drawRect(x + 8 , canvas.getHeight() - y + 4, x + 12, (canvas.getHeight() - y) + 8, p);
                    canvas.drawRect(x + 8 , canvas.getHeight() - y + 8, x + 12, (canvas.getHeight() - y) + 12, p);



                    x=x+8;
                    i=i+1;

                }

                x=0;

                y=y-8;
            }
            imgThermalView.setImageBitmap(bmp);
        }
    }

    protected void createScaledDataBitmap() {

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = Bitmap.createBitmap(32, 24, conf); // this creates a MUTABLE bitmap
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(5f);

        int colorInt = 0;

        int x = 0;
        int y = 24;
        int i = 0;

        int colorIndex = 0;

        if (frameBuffer.size() > 0) {
            float[] pixels = frameBuffer.pop();

            createInterpolatedFrame(pixels);

            while  (y>0) {

                while (x<32) {


                    colorIndex = getColorIndex(pixels[i]);
                    p.setColor(colorMap[colorIndex]);
                    p.setAlpha(255);

                    canvas.drawPoint(x,canvas.getHeight() - y,p);

                    //canvas.drawRect(x, canvas.getHeight() - y, x + 10, (canvas.getHeight() - y) + 10, p);

                    x=x+1;
                    i=i+1;
                }

                x=0;

                y=y-1;

            }



            imgThermalView.setImageBitmap(Bitmap.createScaledBitmap(bmp, 640, 480, true));
        }
    }

    protected void createScaledDataBitmap2() {

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = Bitmap.createBitmap(64, 48, conf); // this creates a MUTABLE bitmap
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        //p.setAlpha(255);
        p.setColor(Color.WHITE);
        p.setTextSize(5f);

        int colorInt = 0;

        int x = 0;
        int y = 0;
        int i = 0;

        int colorIndex = 0;

        if (frameBuffer.size() > 0) {
            float[] tempData = frameBuffer.pop();

            int[][] pixels = createInterpolatedFrame(tempData);

            while (y<46) {
                while (x < 63) {
                    p.setColor(colorMap[pixels[x][y]]);
                    if (pixels[x][y] < 510) {
                        p.setAlpha(pixels[x][y]/2);
                    } else {
                        p.setAlpha(255);
                    }

                    canvas.drawPoint(x,y,p);
                    x++;
                }
                y++;
                x=0;
            }

            p.setColor(Color.WHITE);
            canvas.drawLine(31, 20, 31, 27, p);
            canvas.drawLine(28, 23, 35, 23, p);

            //thermalImage.setImageBitmap(bmp);

            imgThermalView.setImageBitmap(Bitmap.createScaledBitmap(bmp, 640, 480, true));
        }
    }

    protected void createRawDataBitmap() {

        Bitmap.Config conf = Bitmap.Config.ARGB_8888; // see other conf types
        Bitmap bmp = Bitmap.createBitmap(320, 240, conf); // this creates a MUTABLE bitmap
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(5f);

        int colorInt = 0;

        int x = 0;
        int y = 240;
        int i = 0;

        int colorIndex = 0;

        if (frameBuffer.size() > 0) {
            float[] pixels = frameBuffer.pop();

            while  (y>0) {

                while (x<320) {

                    colorIndex = getColorIndex(pixels[i]);
                    p.setColor(colorMap[colorIndex]);
                    p.setAlpha(255);

                    canvas.drawRect(x, canvas.getHeight() - y, x + 10, (canvas.getHeight() - y) + 10, p);

                    x=x+10;
                    i=i+1;
                }

                x=0;

                y=y-10;

            }
            //thermalImage.setImageBitmap(bmp);

            imgThermalView.setImageBitmap(Bitmap.createScaledBitmap(bmp, 640, 480, true));
        }
    }

    private void updateUI() {


        //createRawDataBitmap();
        //createInterpolatedBitmap();
        //createSciFiBitmap();
        //createScaledDataBitmap();
        createScaledDataBitmap2();


        uiUpdateHandler.sendEmptyMessageDelayed(UPDATE_UI, 100);

    }

    public class ClientListen implements Runnable {
        public boolean run = true;

        float pixels[] = new float[768];

        @Override
        public void run() {


            try {

                ServerSocket s = new ServerSocket(0);
                System.out.println("listening on port: " + s.getLocalPort());
                port = s.getLocalPort();
                s.close();

                InetAddress serverAddr = InetAddress.getByName("192.168.4.1");

                Log.d("TCP Client", "C: Connecting...");

                //create a socket to make the connection with the server
                Socket socket = new Socket(serverAddr, 88);
                PrintWriter printWriter = new PrintWriter(socket.getOutputStream());
                printWriter.write(String.valueOf(port));
                printWriter.flush();
                printWriter.close();
                s.close();


            } catch (IOException e) {
                Log.e("UDP client has IOException", "error: ", e);
                run = false;
            }


            try {
                DatagramSocket udpSocket = null;

                udpSocket = new DatagramSocket(port);
                udpSocket.setSoTimeout(2000);

                while (run) {

                    synchronized (this) {

                        byte[] message = new byte[1025];
                        DatagramPacket packet = new DatagramPacket(message, message.length);
                        //Log.i("UDP client: ", "about to wait to receive");
                        udpSocket.receive(packet);

                        //Log.d("Received data", "Received: " + message.length);
                        byte[] bytes = new byte[4];
                        float f = 0;
                        int messageIndex = 1;
                        int i = 0;
                        int stop = 0;


                        maxTemp = -1000;
                        minTemp = 1000;

                        if (message[0] == 49) {
                            i = 0;
                            stop = 256;
                        } else if (message[0] == 50) {
                            i = 256;
                            stop = 512;
                        } else if (message[0] == 51) {
                            i = 512;
                            stop = 768;
                        }


                        for (; i < stop; i++) {
                            bytes[0] = message[messageIndex];
                            messageIndex++;
                            bytes[1] = message[messageIndex];
                            messageIndex++;
                            bytes[2] = message[messageIndex];
                            messageIndex++;
                            bytes[3] = message[messageIndex];
                            messageIndex++;

                            f = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                            pixels[i] = f;

                        }

                        if (message[0]==51) {
                            addFrame(pixels);
                        }

//                        minF = ((minTemp * 9) / 5) + 32;
//                        maxF = ((maxTemp * 9) / 5) + 32;
                    }



                }

                if (udpSocket != null) {
                    udpSocket.close();
                }


            } catch (SocketTimeoutException e) {
                showToast("Connection Timeout");
            }
            catch (IOException e) {
                Log.e("UDP client has IOException", "error: ", e);
                run = false;
            }
        }
    }
}
