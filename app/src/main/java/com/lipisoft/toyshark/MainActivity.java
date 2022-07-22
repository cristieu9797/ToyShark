/*
 *  Copyright 2016 Lipi C.H. Lee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.lipisoft.toyshark;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import static android.system.OsConstants.IPPROTO_TCP;

public class MainActivity extends AppCompatActivity implements AppsLoadListener {
	private static String TAG = "MainActivity";
	private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 0;
	private Button btnStartVpn, btnChooseApp;
	AppsListView mOpenAppsList;
	private TextView mEmptyAppsView;
	public static String TEST_APP = "com.example.apicalltestforvpnapp";




	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.packet_list);

		btnStartVpn = findViewById(R.id.btn_start_vpn);
		btnChooseApp = findViewById(R.id.btn_choose_app);

		final RecyclerView recyclerView = findViewById(R.id.packet_list_recycler_view);
		recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
		recyclerView.setHasFixedSize(true);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		final PacketListAdapter adapter = new PacketListAdapter(PacketManager.INSTANCE.getList());
		PacketManager.INSTANCE.setAdapter(adapter);
		recyclerView.setAdapter(adapter);

		final RecyclerView recyclerViewConnections = findViewById(R.id.connections_recycler_view);
		recyclerViewConnections.setHasFixedSize(true);
		recyclerViewConnections.setLayoutManager(new LinearLayoutManager(this));

		// create a deep copy I believe of an empty list, seems useless

		final ConnectionListAdapter adapterConnections = new ConnectionListAdapter(SessionManager.INSTANCE.getList());
		SessionManager.INSTANCE.setAdapter(adapterConnections);
		recyclerViewConnections.setAdapter(adapterConnections);

		btnStartVpn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				checkRuntimePermission();
			}
		});

		btnChooseApp.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				openAppFilterSelector();
			}
		});

	}

	private void openAppFilterSelector() {
		Dialog dialog = getAppSelectionDialog(this, new ArrayList<>(), this::setAppFilter);
		dialog.setOnCancelListener(dialog1 -> {
			setAppFilter(null);
		});
		dialog.setOnDismissListener(dialog1 -> {
			mOpenAppsList = null;
			mEmptyAppsView = null;
		});

		dialog.show();

		// NOTE: run this after dialog.show
		mOpenAppsList = (AppsListView) dialog.findViewById(R.id.apps_list);
		mEmptyAppsView = dialog.findViewById(R.id.no_apps);
		mEmptyAppsView.setText("Loading apps...");

		(new AppsLoader(this))
				.setAppsLoadListener(this)
				.loadAllApps();
	}

	private void setAppFilter(AppDescriptor filter){
		if(filter != null) {
			Log.e("TAGDEBUG", "setAppFilter: " + filter.getPackageName() + " " + filter.getUid());
//			TEST_APP = String.valueOf(filter.getUid());
			TEST_APP = filter.getPackageName();
		}
	}

	public Dialog getAppSelectionDialog(Activity activity, List<AppDescriptor> appsData, AppsListView.OnSelectedAppListener listener) {
		View dialogLayout = activity.getLayoutInflater().inflate(R.layout.apps_selector, null);
		SearchView searchView = dialogLayout.findViewById(R.id.apps_search);
		AppsListView apps = dialogLayout.findViewById(R.id.apps_list);
		TextView emptyText = dialogLayout.findViewById(R.id.no_apps);

		apps.setApps(appsData);
		apps.setEmptyView(emptyText);
		searchView.setOnQueryTextListener(apps);

		androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(activity);
		builder.setTitle("Filtru apps");
		builder.setView(dialogLayout);

		final androidx.appcompat.app.AlertDialog alert = builder.create();
		alert.setCanceledOnTouchOutside(true);

		apps.setSelectedAppListener(app -> {
			listener.onSelectedApp(app);

			// dismiss the dialog
			alert.dismiss();
		});

		return alert;
	}

	void checkRuntimePermission() {
		int permission = ContextCompat.checkSelfPermission(this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE);
		if (permission != PackageManager.PERMISSION_GRANTED) {
			if (ActivityCompat.shouldShowRequestPermissionRationale(this,
					Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
				// TODO inform the user to ask runtime permission
				Log.d(TAG, "explains permission is needed.");
			} else {
				ActivityCompat.requestPermissions(this,
						new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE },
						REQUEST_WRITE_EXTERNAL_STORAGE);
			}
		} else {
			if (networkAndAirplaneModeCheck()){
				btnStartVpn.setEnabled(false);
				startVPN();
			} else {
				showInfoDialog(getResources().getString(R.string.app_name),
						getResources().getString(R.string.no_network_information));
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		switch (requestCode) {
			case REQUEST_WRITE_EXTERNAL_STORAGE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					if (networkAndAirplaneModeCheck()) {
						startVPN();
					} else {
						showInfoDialog(getResources().getString(R.string.app_name),
								getResources().getString(R.string.no_network_information));
					}
				}
		}
	}

	/**
	 * Launch intent for user approval of VPN connection
	 */
	private void startVPN() {
		// check for VPN already running
		try {
			if (!checkForActiveInterface("tun0")) {

				// get user permission for VPN
				Intent intent = VpnService.prepare(this);
				if (intent != null) {
					Log.d(TAG, "ask user for VPN permission");
					startActivityForResult(intent, 0);
				} else {
					Log.d(TAG, "already have VPN permission");
					onActivityResult(0, RESULT_OK, null);
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception checking network interfaces :" + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * check a network interface by name
	 *
	 * @param networkInterfaceName Network interface Name on Linux, for example tun0
	 * @return true if interface exists and is active
	 * @throws Exception throws Exception
	 */
	private boolean checkForActiveInterface(String networkInterfaceName) throws Exception {
		List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
		for (NetworkInterface networkInterface : interfaces) {
			if (networkInterface.getName().equals(networkInterfaceName)) {
				return networkInterface.isUp();
			}
		}
		return false;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.i(TAG, "onActivityResult(resultCode:  " + resultCode + ")");
		if (resultCode == RESULT_OK) {
			Intent captureVpnServiceIntent = new Intent(getApplicationContext(), ToySharkVPNService.class);
//			captureVpnServiceIntent.putExtra("TRACE_DIR", Environment.getExternalStorageDirectory().getPath() + "/ToyShark");
			captureVpnServiceIntent.putExtra("TRACE_DIR", Environment
					.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/ToyShark");
			startService(captureVpnServiceIntent);
		} else if (resultCode == RESULT_CANCELED) {
			showVPNRefusedDialog();
		}
	}

	/**
	 * Show dialog to educate the user about VPN trust
	 * abort app if user chooses to quit
	 * otherwise relaunch the startVPN()
	 */
	private void showVPNRefusedDialog() {
		new AlertDialog.Builder(this)
				.setTitle("Usage Alert")
				.setMessage("You must trust the ToyShark in order to run a VPN based trace.")
				.setPositiveButton(getString(R.string.try_again), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						startVPN();
					}
				})
				.setNegativeButton(getString(R.string.quit), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						finish();
					}
				})
				.show();

	}

	/**
	 * @param title Title in Dialog
	 * @param message Message in Dialog
	 */
	private void showInfoDialog(String title, String message) {
		new AlertDialog.Builder(this)
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						//finish();
					}
				})
				.show();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	protected void onStop() {
		Log.i(TAG, "onStop()");
		super.onStop();
	}

	@Override
	protected void onResume() {
		Log.i(TAG, "onResume");
		super.onResume();
	}

	@Override
	public void onBackPressed() {
		Log.i(TAG, "onBackPressed");
		super.onBackPressed();
	}

	/** check whether network is connected or not
	 *  @return boolean
	 */
	private boolean isConnectedToInternet() {
		ConnectivityManager connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		if (connectivity != null) {
			NetworkInfo networkInfo = connectivity.getActiveNetworkInfo();
			if (networkInfo != null && networkInfo.isConnected()) {
				return true;
			}
		}
		return false;
	}

	private boolean networkAndAirplaneModeCheck() {
//		if (!isConnectedToInternet()) {
//			final String title = "ToyShark";
//			final String message = "No network connection in your phone, Connect to network and start again";
//			showInfoDialog(title, message);
//			return false;
//		}
//		return true;
		return isConnectedToInternet();
	}

	@Override
	public void onAppsInfoLoaded(List<AppDescriptor> installedApps) {
		if(mOpenAppsList == null)
			return;

		mEmptyAppsView.setText("no apps found");

		// Load the apps/icons
		Log.d(TAG, "loading " + installedApps.size() +" apps in dialog, icons=" + installedApps);
		mOpenAppsList.setApps(installedApps);
	}
}
