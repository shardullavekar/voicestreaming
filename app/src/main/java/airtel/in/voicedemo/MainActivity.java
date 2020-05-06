package airtel.in.voicedemo;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;


public class MainActivity extends AppCompatActivity {

    private Button startButton,stopButton;
    private TypeWriter textView;
    WebSocket ws;

    AudioRecord recorder;

    private int sampleRate = 16000 ; // 44100 for music
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    private boolean status = true;
    private OkHttpClient client;
    Thread streamThread;

    private final class EchoWebSocketListener extends WebSocketListener {
        private static final int NORMAL_CLOSURE_STATUS = 1000;
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d("STTRESPONSE","opened");
            //webSocket.close(NORMAL_CLOSURE_STATUS, "Goodbye !");
        }
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject jsonObject = new JSONObject(text);
                output(jsonObject.getString("text"));

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            output("Receiving bytes : " + bytes.hex());
        }
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(NORMAL_CLOSURE_STATUS, null);
            output("Closing : " + code + " / " + reason);
        }
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            output("Error : " + t.getMessage());
        }
    }

    private void start() {
        Request request = new Request.Builder().url("ws://ec2-52-12-162-217.us-west-2.compute.amazonaws.com:8080").build();
        EchoWebSocketListener listener = new EchoWebSocketListener();
        ws = client.newWebSocket(request, listener);
        client.dispatcher().executorService().shutdown();
    }
    private void output(final String txt) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d("STTRESPONSE", txt);
                textView.setCharacterDelay(50);
                textView.animateText(textView.getText() + "\n" + txt);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = (Button) findViewById (R.id.button);
        stopButton = (Button) findViewById (R.id.button2);
        textView = (TypeWriter) findViewById(R.id.incoming);

        startButton.setOnClickListener (startListener);
        stopButton.setOnClickListener (stopListener);
        client = new OkHttpClient();
    }

    private final OnClickListener stopListener = new OnClickListener() {

        @Override
        public void onClick(View arg0) {
            status = false;
            recorder.release();
            streamThread.interrupt();
            textView.setText("");
            Log.d("VS","Recorder released");
        }

    };

    private final OnClickListener startListener = new OnClickListener() {

        @Override
        public void onClick(View arg0) {
            status = true;
            startStreaming();
        }

    };

    public void startStreaming() {


        streamThread = new Thread(new Runnable() {

            @Override
            public void run() {
                start();
                byte[] buffer = new byte[minBufSize];

                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,channelConfig,audioFormat,minBufSize*10);
                Log.d("VS", "Recorder initialized");

                recorder.startRecording();
                int counter = 0;

                while(status == true) {

                    //reading data from MIC into buffer
                    minBufSize = recorder.read(buffer, 0, buffer.length);

                    ws.send(ByteString.of(buffer));
                    System.out.println("MinBufferSize: " +minBufSize);
                    counter = counter + 1;
                    if(counter > 500) { break;}
                }

                JSONObject jsonObject = new JSONObject();
                try {
                    jsonObject.put("eof", 1);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                ws.send(jsonObject.toString());

            }

        });
        streamThread.start();
    }
}
