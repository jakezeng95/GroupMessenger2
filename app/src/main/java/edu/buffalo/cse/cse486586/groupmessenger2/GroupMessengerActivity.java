package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 *
 */
public class GroupMessengerActivity extends Activity {

    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String[] REMOTE_PORTS = {"11108", "11112", "11116", "11120", "11124"};
    static final int SERVER_PORT = 10000;
    static Set<String> FAILED_PORT = new HashSet<String>();
    int fifo_seq = 0;
    String failPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);


        /*
         * TODO: Use the TextView to display your messages. Though there is no grading component
         * on how you display the messages, if you implement it, it'll make your debugging easier.
         */
        final TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());



        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        //code from PA1
        TelephonyManager tel = (TelephonyManager)  this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        try{
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        }catch (IOException e){
            Log.e(TAG, "Can't create a ServerSocket");
            return;
        }
        /*
         * TODO: You need to register and implement an OnClickListener for the "Send" button.
         * In your implementation you need to get the message from the input box (EditText)
         * and send it to other AVDs.
         */
        final EditText editText1 = (EditText) findViewById(R.id.editText1);
        findViewById(R.id.button4).setOnClickListener(
                new View.OnClickListener(){
                    @Override
                    public void onClick(View v){
                        String msg = editText1.getText().toString();
                        editText1.setText("");
                        //tv.append(msg + "\n");

                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
                        return;
                    }

                }
        );
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }
    public class message implements Comparable<message>{
        String clientPort;
        String content;
        boolean agreed;
        int fifo_seq;
        int total_seq;
        public message(String text,String client, int fifo, int total, boolean agree){
            content = text;
            clientPort = client;
            fifo_seq = fifo;
            total_seq = total;
            agreed = agree;
        }
        @Override
        public int compareTo(message m2){
            if (Integer.valueOf(this.clientPort) == Integer.valueOf(m2.clientPort)){
                return this.fifo_seq - m2.fifo_seq;
            }else if (this.total_seq == m2.total_seq){
                return Integer.valueOf(this.clientPort) - Integer.valueOf(m2.clientPort);
            }
            return this.total_seq - m2.total_seq;
        }
    }


    private class ServerTask extends AsyncTask<ServerSocket, String, Void>{

        private int count = 0;
        private ContentResolver cr = getContentResolver();
        Uri uri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
        @Override
        protected Void doInBackground(ServerSocket... sockets){
            ServerSocket serverSocket = sockets[0];

            int total_seq = 0;
            PriorityQueue<message> pq = new PriorityQueue<message>();

            while (true){
                try {
                    Socket socket = serverSocket.accept();
                    DataInputStream in = new DataInputStream(
                            new BufferedInputStream(socket.getInputStream())
                    );
                    DataOutput out = new DataOutputStream(
                            new BufferedOutputStream(socket.getOutputStream())
                    );

                    String line = in.readUTF();
//                    System.out.println(line);
                    String[] strs = line.split(",");

                    if (strs[0].equals("agreed")){
                        int agreedSeq = Integer.valueOf(strs[1]);
                        total_seq = Math.max(agreedSeq, total_seq);
                        String clientPort = strs[3];
                        int fifoSeq = Integer.valueOf(strs[2]);
                        Iterator it = pq.iterator();
                        while (it.hasNext()) {
                            message temp = (message) it.next();
                            if (temp.fifo_seq == fifoSeq && temp.clientPort.equals(clientPort)) {
                                it.remove();
                                pq.add(new message(strs[4], clientPort, fifoSeq, agreedSeq, true));
                                break;
                            }
                        }
                        while (pq.peek() != null && pq.peek().agreed) {

                            message head = pq.poll();
                            publishProgress(head.content);
                            //System.out.println(head.content);
                        }
                    }
                    else if (strs[0].equals("inform")){
                        failPort = strs[1];
                        FAILED_PORT.add(strs[1]);
                        //System.out.println("remove" + strs[1]);
                        Iterator it = pq.iterator();
                        while(it.hasNext()){
                            message temp = (message) it.next();
                            if(temp.clientPort.equals(strs[1]) && !temp.agreed){
                                it.remove();
                            }
                        }
                    }
                    else {
                        total_seq += 1;
                        message msg = new message(strs[2], strs[1], Integer.valueOf(strs[0]), total_seq, false);
                        pq.add(msg);
                        out.writeUTF(Integer.toString(total_seq));
                        ((DataOutputStream) out).close();

                    }
                    in.close();
                    socket.close();
                }catch (IOException e){
                    publishProgress(e.toString());
                    System.out.print(e);
                }

            }
        }
        protected void onProgressUpdate(String... strings){
            String strReceived = strings[0].trim();

            TextView textView1 = (TextView) findViewById(R.id.textView1);
            textView1.append(strReceived + "\t\n");


            FileOutputStream outputStream;
            try {

                ContentValues cv = new ContentValues();
                String fileName = Integer.toString(count);
                cv.put("key", Integer.toString(count));
                count++;
                cv.put("value", strReceived);
                cr.insert(uri, cv);
                outputStream = openFileOutput(fileName, Context.MODE_PRIVATE);
                outputStream.write(strReceived.getBytes());
                outputStream.close();


            } catch (Exception e) {
                Log.e(TAG, "File write failed");
            }

            return;

        }
        //method from OnPTestClickListener.java
        private Uri buildUri(String scheme, String authority) {
            Uri.Builder uriBuilder = new Uri.Builder();
            uriBuilder.authority(authority);
            uriBuilder.scheme(scheme);
            return uriBuilder.build();
        }


    }
    private class ClientTask extends AsyncTask<String, Void, Void>{

        @Override
        protected Void doInBackground(String... msgs){
            fifo_seq += 1;
            List<Integer> propoArray = new ArrayList<Integer>();

            for (String remote_port: REMOTE_PORTS) {
                if (!FAILED_PORT.contains(remote_port)){
                    try {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remote_port));

                        String msgToSend = Integer.toString(fifo_seq) + "," + msgs[1] + "," + msgs[0];
                        DataOutput out = new DataOutputStream(socket.getOutputStream());
                        out.writeUTF(msgToSend);

                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        socket.setSoTimeout(500);
                        try {
                            String proposed_str = in.readUTF();
                            int proposedSeq = Integer.valueOf(proposed_str);
                            propoArray.add(proposedSeq);

                        } catch (IOException e) {
                            System.out.print(e);
                            failPort = remote_port;
                            FAILED_PORT.add(failPort);

                            ((DataOutputStream) out).close();
                            socket.close();
                            //System.out.println("failed port found" + remote_port);
                            informFailure();
                        }

                        if (!socket.isClosed()) {
                            ((DataOutputStream) out).close();
                            socket.close();
                        }
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "ClientTask UnknownHostException");
                    } catch (IOException e) {
                        Log.e(TAG, "ClientTask socket IOException");
                    }
            }
            }
            int agreedSeq = Collections.max(propoArray);
            for (String remote_port: REMOTE_PORTS) {
                if (!FAILED_PORT.contains(remote_port)) {
                    try {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remote_port));
                        String agreedMsg = "agreed," + Integer.toString(agreedSeq) + "," +
                                Integer.toString(fifo_seq) + "," + msgs[1] + "," + msgs[0];

                        DataOutput out = new DataOutputStream(socket.getOutputStream());
                        out.writeUTF(agreedMsg);
                        ((DataOutputStream) out).close();
                        socket.close();

                    } catch (UnknownHostException e) {
                        Log.e(TAG, "ClientTask UnknownHostException");
                    } catch (IOException e) {
                        Log.e(TAG, "ClientTask socket IOException");
                    }
                }
            }
            return null;
        }
        private void informFailure(){

            for (String remote_port: REMOTE_PORTS) {
                if (!FAILED_PORT.contains(remote_port)) {
                    try {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remote_port));
                        String informMsg = "inform," + failPort;
                        System.out.println("send to " + remote_port);

                        DataOutput out = new DataOutputStream(socket.getOutputStream());
                        out.writeUTF(informMsg);
                        ((DataOutputStream) out).close();
                        socket.close();

                    } catch (UnknownHostException e) {
                        Log.e(TAG, "ClientTask UnknownHostException");
                    } catch (IOException e) {
                        Log.e(TAG, "ClientTask socket IOException");
                    }
                }

            }
        }
    }
}