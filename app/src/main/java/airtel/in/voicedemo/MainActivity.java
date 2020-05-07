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
import android.widget.Toast;

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
    private int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    private boolean status = true;
    private OkHttpClient client;
    Thread streamThread;
    short threshold=15000;

    private final class EchoWebSocketListener extends WebSocketListener {
        private static final int NORMAL_CLOSURE_STATUS = 1000;
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d("STTRESPONSE","opened");
        }
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            JSONObject jsonObject;
            try {
                Log.d("ALLTEXT", text);
                jsonObject = new JSONObject(text);

                if (jsonObject.has("text")) {
                    output(jsonObject.getString("text"));
                    stopRecording();
                    startRecording();
                }

                if (jsonObject.has("partial")) {
                    output(jsonObject.getString("partial"));
                }


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
        Request request = new Request.Builder().url("ws://").build();
        EchoWebSocketListener listener = new EchoWebSocketListener();
        ws = client.newWebSocket(request, listener);
        client.dispatcher().executorService().shutdown();
    }
    private void output(final String txt) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (txt.isEmpty()) {
                    return;
                }
                Log.d("STTRESPONSE", txt);
                textView.setText(txt);
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
        start();
    }

    private final OnClickListener stopListener = new OnClickListener() {

        @Override
        public void onClick(View arg0) {
            stopRecording();
            textView.setText("");
        }

    };

    private final OnClickListener startListener = new OnClickListener() {

        @Override
        public void onClick(View arg0) {
            startRecording();
        }

    };

    private void startRecording() {
        status = true;
        startStreaming();
    }

    private void stopRecording() {
        status = false;
        recorder.release();
        streamThread.interrupt();
        Log.d("ALLTEXT","Recorder released");
    }

    private class StreamVoice implements Runnable {

        @Override
        public void run() {
            short[] buffer = new short[AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)];

            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,channelConfig,audioFormat,AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)*10);
            Log.d("VS", "Recorder initialized");

            recorder.startRecording();
            int counter = 0;

            while(status == true) {

                minBufSize = recorder.read(buffer, 0, buffer.length);

                if(AudioRecord.ERROR_INVALID_OPERATION != minBufSize){
                    int foundPeak=searchThreshold(buffer,threshold);
                    if (foundPeak>-1){ //found signal
                        //record signal
                        byte[] byteBuffer =ShortToByte(buffer, minBufSize);
                        ws.send(ByteString.of(byteBuffer));
                        counter = 0;
                    } else{
                        counter = counter + 1;
                        Log.d("STTRESPONSE", "silence detected-" + String.valueOf(counter));
                        if (counter > 50) {
                            stopRecording();
                            Log.d("STTRESPONSE", "Stopping because no speech is detected for 50 counter");
                            MainActivity.this.runOnUiThread(new Runnable() {
                                public void run() {
                                    Toast.makeText(getApplicationContext(), "Stopped because no speech for 5 seconds", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                        else {
                            byte[] byteBuffer =ShortToByte(buffer, minBufSize);
                            ws.send(ByteString.of(byteBuffer));
                        }
                    }
                }

                System.out.println("MinBufferSize: " +minBufSize);
            }

            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("eof", 1);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            ws.send(jsonObject.toString());
        }
    }

    public void startStreaming() {
        StreamVoice streamVoice = new StreamVoice();
        streamThread = new Thread(streamVoice);
        streamThread.start();
    }

    byte [] ShortToByte(short [] input, int elements) {
        int short_index, byte_index;
        int iterations = elements; //input.length;
        byte [] buffer = new byte[iterations * 2];

        short_index = byte_index = 0;

        for(/*NOP*/; short_index != iterations; /*NOP*/)
        {
            buffer[byte_index]     = (byte) (input[short_index] & 0x00FF);
            buffer[byte_index + 1] = (byte) ((input[short_index] & 0xFF00) >> 8);

            ++short_index; byte_index += 2;
        }

        return buffer;
    }

    int searchThreshold(short[]arr,short thr){
        int peakIndex;
        int arrLen=arr.length;
        for (peakIndex=0;peakIndex<arrLen;peakIndex++){
            if ((arr[peakIndex]>=thr) || (arr[peakIndex]<=-thr)) {
                return peakIndex;
            }
        }
        return -1; //not found
    }
}
