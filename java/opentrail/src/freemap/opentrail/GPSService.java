package freemap.opentrail;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.Location;
import android.os.Bundle;
import android.content.Context;
import freemap.data.Walkroute;
import freemap.data.TrackPoint;
import android.content.BroadcastReceiver;
import android.os.IBinder;
import android.content.IntentFilter;
import android.widget.Toast;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import freemap.datasource.WalkrouteCacheManager;


public class GPSService extends Service implements LocationListener {

	
	
	boolean isLogging, listeningForUpdates, firstStart;
	LocationManager mgr;
	long lastUpdateTime = 0L;
	GPSServiceReceiver receiver;
	static final long LOGGING_INTERVAL = 5000L, NO_LOGGING_INTERVAL = 10000L;
	WalkrouteCacheManager wrCacheMgr;
	Walkroute recordingWalkroute;
	
	
	
	class SaveWalkrouteTask extends AsyncTask<Void,Void,Boolean>
	{
		public Boolean  doInBackground(Void... data)
		{
			boolean retcode=true;
			try
			{
				if(wrCacheMgr!=null)
				    wrCacheMgr.addRecordingWalkroute(recordingWalkroute);
				
			}
			catch(java.io.IOException e)
			{
				retcode=false;
			}
			return retcode;
		}
	}
	
	class Binder extends android.os.Binder
	{
		public GPSService getService()
		{
			return GPSService.this;
		}
	}
	
	Binder binder;
	
	public void onCreate()
	{
		super.onCreate();
		binder = new GPSService.Binder(); 
        firstStart=true;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isLogging = prefs.getBoolean("gpsServiceIsLogging", false);
	}
	
	// called typically by an Activity's onStart()
	// intent will be null if the service dies and is restarted
	public int onStartCommand(Intent intent, int startFlags, int id)
	{
	    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
	    String wrCacheLoc = prefs.getString("wrCacheLoc",null);
	    if(receiver==null)
		{
			receiver=new GPSServiceReceiver(this);
			IntentFilter filter = new IntentFilter();
			filter.addAction("freemap.opentrail.startlogging");
			filter.addAction("freemap.opentrail.stoplogging");
			filter.addAction("freemap.opentrail.stopifnotlogging");
			registerReceiver(receiver, filter);
		}
		
		if(wrCacheMgr==null && wrCacheLoc!=null)
			wrCacheMgr = new WalkrouteCacheManager(wrCacheLoc);
		
		// start updates if not already started
		mgr = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		//Log.d("OpenTrail","trying to start gps updates");
		
		if(firstStart)
		{
		    // To recover from the service possibly being killed - try to reload a
		    // recording walkroute if there is one.
		  
		    firstStart=false;
		    try
	        {
		        if(wrCacheMgr!=null)
		            recordingWalkroute=wrCacheMgr.getRecordingWalkroute();
	        }
	        catch(Exception e)
	        { 
	            Toast.makeText(getApplicationContext(), "Previous walk route corrupted, starting new one", Toast.LENGTH_LONG).show();
	        }
	    
	        if(recordingWalkroute==null)
	            recordingWalkroute = new Walkroute();
		}
		
	    if(!listeningForUpdates)
	    {
			//Log.d("OpenTrail","not listening so starting");
			listeningForUpdates=true;
			mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, isLogging? 
			                LOGGING_INTERVAL : NO_LOGGING_INTERVAL, 10, this);
			Intent broadcast = new Intent("freemap.opentrail.startupdates");
			this.sendBroadcast(broadcast);
		}
		return START_STICKY;
	}
	
	public void onDestroy()
	{
		super.onDestroy();
		mgr.removeUpdates(this);
		unregisterReceiver(receiver);
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		SharedPreferences.Editor ed = prefs.edit();
		ed.putBoolean("gpsServiceIsLogging", isLogging);
		ed.commit();
		receiver=null;
	}
	
	// called if user starts a recording session
	public void startLogging()
	{
		isLogging=true;
		
		// adjust interval to 5 secs as we are logging
		LocationManager mgr = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		mgr.removeUpdates(this);
		mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOGGING_INTERVAL, 10, this);
	}
	
	// called if user ends a recording session
	public void stopLogging()
	{
		isLogging=false;
		
		// adjust interval to 10 secs as we are not logging
		LocationManager mgr = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		mgr.removeUpdates(this);
		mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, NO_LOGGING_INTERVAL, 10, this);
		
		// save walkroute before stopping
		new SaveWalkrouteTask().execute();
		
	}
	
	// called by activity onStop() typically - stop updates if no logging
	public void stopIfNotLogging()
	{
		if(!isLogging)
		{
			listeningForUpdates=false;
			mgr.removeUpdates(this);
			stopSelf();
		}
	}
	

	
	
	public void onLocationChanged(Location loc)
	{
		boolean refresh=false;
		
		long time = System.currentTimeMillis();
		TrackPoint tp = new TrackPoint(loc.getLongitude(), loc.getLatitude(), time);
		if(isLogging)
		{
			recordingWalkroute.addPoint(tp);
			
			if(time - lastUpdateTime > 30000 &&  isLogging)
			{
				new SaveWalkrouteTask().execute();
				lastUpdateTime = time;
			}
			
			refresh=true;			
		}
		
	
		
		
		// send msg to any receivers informing them of location
		Intent broadcast = new Intent("freemap.opentrail.locationchanged");
		broadcast.putExtra("lat", loc.getLatitude());
		broadcast.putExtra("lon", loc.getLongitude());
		broadcast.putExtra("refresh", refresh);
		this.sendBroadcast(broadcast);
	}
	
	public void onProviderEnabled(String provider)
	{
		// pass on msg
		Intent broadcast = new Intent("freemap.opentrail.providerenabled");
		broadcast.putExtra("provider", provider);
		broadcast.putExtra("enabled", true);
		this.sendBroadcast(broadcast);
	}
	
	public void onProviderDisabled(String provider)
	{
		// pass on msg
		Intent broadcast = new Intent("freemap.opentrail.providerenabled");
		broadcast.putExtra("provider", provider);
		broadcast.putExtra("enabled", false);
		this.sendBroadcast(broadcast);
	}
	
	public void onStatusChanged(String provider, int status, Bundle extras)
	{
		// pass on msg
		Intent broadcast = new Intent("freemap.opentrail.statuschanged");
		broadcast.putExtra("provider", provider);
		broadcast.putExtra("status", status);
		this.sendBroadcast(broadcast);
	}
	
	public IBinder onBind(Intent intent)
	{  
		return binder;
	}
	
	public Walkroute getRecordingWalkroute()
	{
		return recordingWalkroute;
	}
}

class GPSServiceReceiver extends BroadcastReceiver
{
	GPSService service;
	
	public GPSServiceReceiver(GPSService service)
	{
		this.service=service;
	}
	
	public void onReceive(Context ctx, Intent intent)
	{
		String action = intent.getAction();
		if(service!=null)
		{
			if( action.equals("freemap.opentrail.startlogging"))
				service.startLogging();
			else if (action.equals("freemap.opentrail.stoplogging"))
				service.stopLogging();
			else if(action.equals("freemap.opentrail.stopifnotlogging"))
				service.stopIfNotLogging();
		}
	}
}