package com.example.nighdragon.freefalldetector;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.builder.filter.Comparison;
import com.mbientlab.metawear.builder.filter.ThresholdOutput;
import com.mbientlab.metawear.builder.function.Function1;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.Logging;

import bolts.Continuation;
import bolts.Task;

public class MainActivity extends Activity implements ServiceConnection {
    private BtleService.LocalBinder serviceBinder;
    private MetaWearBoard board;
    private Accelerometer accelerometer;
    private Logging logging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getApplicationContext().bindService(new Intent(this, BtleService.class), this, Context.BIND_AUTO_CREATE);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                Log.i("frefall","start" );
                logging.start(false);
                accelerometer.acceleration().start();
                accelerometer.start();
            }
        });
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                Log.i("freefall", "stop");
                accelerometer.stop();
                accelerometer.acceleration().stop();
                logging.stop();
                logging.downloadAsync().continueWith(new Continuation<Void, Void>() {
                    @Override
                    public Void then(Task<Void> task) throws Exception{
                        if(task.isFaulted()){
                            Log.i("freefall", "Log download failed");
                        }
                        else
                        {
                            Log.i("freefall", "log downlaod complete");
                        }
                        return null;
                    }
                });
            }
        });
        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                board.tearDown();;
            }
        });
    }

    @Override
    public void onDestroy(){
        super.onDestroy();

        getApplicationContext().unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        serviceBinder = (BtleService.LocalBinder) service;
        Log.i("freefall", "Service Connected");
        retrieveBoard("FA:71:76:28:09:B9");
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    private void retrieveBoard(String macAddr) {
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice = btManager.getAdapter().getRemoteDevice(macAddr);

        board = serviceBinder.getMetaWearBoard(remoteDevice);
        board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {
                Log.i("freefall", "connect to " + macAddr);

                logging = board.getModule(Logging.class);
                accelerometer = board.getModule(Accelerometer.class);
                accelerometer.configure()
                        .odr(60f)
                        .commit();
                return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.map(Function1.RSS).average((byte) 4).filter(ThresholdOutput.BINARY, 0.5f)
                                .multicast()
                                .to().filter(Comparison.EQ, -1).log(new Subscriber(){
                                    @Override
                                    public void apply(Data data, Object... env) {
                                        Log.i("frefall", data.formattedTimestamp() + ": in freefall");
                                    }
                                })
                                .to().filter(Comparison.EQ, 1).log(new Subscriber(){
                                    @Override
                                    public void apply(Data data, Object... env) {
                                        Log.i("frefall", data.formattedTimestamp() + ": no freefall");
                                    }
                                })
                                .end();
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {
                if (task.isFaulted()) {
                    Log.w("freefall", "Failed to configure app", task.getError());
                } else {
                    Log.i("freefall", "App configured");
                }

                return null;
            }
        });
    }
}
