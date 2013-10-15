/** 
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * 
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

//
// CanvasView is the Activity for showing VNC Desktop.
//
package com.iiordanov.bVNC;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.iiordanov.android.bc.BCFactory;

import com.iiordanov.android.zoomer.ZoomControls;
import com.iiordanov.bVNC.input.RemoteKeyboard;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.View.OnKeyListener;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;


public class VncCanvasActivity extends Activity implements OnKeyListener {
	
	private final static String TAG = "VncCanvasActivity";

	AbstractInputHandler inputHandler;

	VncCanvas vncCanvas;

	VncDatabase database;

	private MenuItem[] inputModeMenuItems;
	private MenuItem[] scalingModeMenuItems;
	private AbstractInputHandler inputModeHandlers[];
	private ConnectionBean connection;
/*	private static final int inputModeIds[] = { R.id.itemInputFitToScreen,
		R.id.itemInputTouchpad,
		R.id.itemInputMouse, R.id.itemInputPan,
		R.id.itemInputTouchPanTrackballMouse,
		R.id.itemInputDPadPanTouchMouse, R.id.itemInputTouchPanZoomMouse };
 */
	private static final int inputModeIds[] = { R.id.itemInputTouchpad,
		                                        R.id.itemInputTouchPanZoomMouse,
		                                        R.id.itemInputDragPanZoomMouse,
		                                        R.id.itemInputSingleHanded };
	private static final int scalingModeIds[] = { R.id.itemZoomable, R.id.itemFitToScreen,
												  R.id.itemOneToOne};

	ZoomControls zoomer;
	Panner panner;
	SSHConnection sshConnection;
	Handler handler;

	RelativeLayout layoutKeys;
	ImageButton    keyStow;
	ImageButton    keyCtrl;
	boolean       keyCtrlToggled;
	ImageButton    keySuper;
	boolean       keySuperToggled;
	ImageButton    keyAlt;
	boolean       keyAltToggled;
	ImageButton    keyTab;
	ImageButton    keyEsc;
	ImageButton    keyUp;
	ImageButton    keyDown;
	ImageButton    keyLeft;
	ImageButton    keyRight;
	boolean       hardKeyboardExtended;
	boolean       extraKeysHidden = false;
    int            prevBottomOffset = 0;


	/**
	 * Function used to initialize an empty SSH HostKey for a new VNC over SSH connection.
	 */
	// TODO: This functionality should go into the handler like displaying 509 certificates.
	private void initializeSshHostKey() {
		// If the SSH HostKey is empty, then we need to grab the HostKey from the server and save it.
		if (connection.getSshHostKey().equals("")) {
			Toast.makeText(this, "Attempting to initialize SSH HostKey.", Toast.LENGTH_SHORT).show();
			Log.d(TAG, "Attempting to initialize SSH HostKey.");
			
			sshConnection = new SSHConnection(connection);
			if (!sshConnection.connect()) {
				// Failed to connect, so show error message and quit activity.
				Utils.showFatalErrorMessage(this,
						"Failed to connect to SSH Server. Please check network connectivity, " +
						"and SSH Server address and port.");
			} else {
				// Show a dialog with the key signature.
				DialogInterface.OnClickListener signatureNo = new DialogInterface.OnClickListener() {
		            @Override
		            public void onClick(DialogInterface dialog, int which) {
		                // We were told to not continue, so stop the activity
		            	sshConnection.terminateSSHTunnel();
		                finish();    
		            }	
		        };
		        DialogInterface.OnClickListener signatureYes = new DialogInterface.OnClickListener() {
		            @Override
		            public void onClick(DialogInterface dialog, int which) {
		    			// We were told to go ahead with the connection.
		    			connection.setSshHostKey(sshConnection.getServerHostKey());
		    			connection.save(database.getWritableDatabase());
		    			database.close();
		    			sshConnection.terminateSSHTunnel();
		    			sshConnection = null;
		            	continueConnecting();
		            }
		        };
		        
				Utils.showYesNoPrompt(this, "Continue connecting to " + connection.getSshServer() + "?", 
									"The host key fingerprint is: " + sshConnection.getHostKeySignature() + 
									".\nYou can ensure it is identical to the known fingerprint of the server certificate to prevent a man-in-the-middle attack.",
									signatureYes, signatureNo);
			}
		} else {
			// There is no need to initialize the HostKey, so continue connecting.
			continueConnecting();
		}
	}
		
	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		initialize();
	}
	
	void initialize () {
		handler = new Handler ();
		
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
							 WindowManager.LayoutParams.FLAG_FULLSCREEN);

		database = new VncDatabase(this);

		Intent i = getIntent();
		connection = new ConnectionBean(this);
		
		Uri data = i.getData();
		if ((data != null) && (data.getScheme().equals("vnc"))) {
			
			// TODO: Can we also handle VNC over SSH/SSL connections with a new URI format?
			
			String host = data.getHost();
			// This should not happen according to Uri contract, but bug introduced in Froyo (2.2)
			// has made this parsing of host necessary
			int index = host.indexOf(':');
			int port;
			if (index != -1)
			{
				try
				{
					port = Integer.parseInt(host.substring(index + 1));
				}
				catch (NumberFormatException nfe)
				{
					port = 0;
				}
				host = host.substring(0,index);
			}
			else
			{
				port = data.getPort();
			}
			if (host.equals(VncConstants.CONNECTION))
			{
				if (connection.Gen_read(database.getReadableDatabase(), port))
				{
					MostRecentBean bean = androidVNC.getMostRecent(database.getReadableDatabase());
					if (bean != null)
					{
						bean.setConnectionId(connection.get_Id());
						bean.Gen_update(database.getWritableDatabase());
		    			database.close();
					}
				}
			} else {
			    connection.setAddress(host);
			    connection.setNickname(connection.getAddress());
			    connection.setPort(port);
			    List<String> path = data.getPathSegments();
			    if (path.size() >= 1) {
			        connection.setColorModel(path.get(0));
			    }
			    if (path.size() >= 2) {
			        connection.setPassword(path.get(1));
			    }
			    connection.save(database.getWritableDatabase());
    			database.close();
			}
		} else {
		
		    Bundle extras = i.getExtras();

		    if (extras != null) {
		  	    connection.Gen_populate((ContentValues) extras
				  	.getParcelable(VncConstants.CONNECTION));
		    }
		    if (connection.getPort() == 0)
			    connection.setPort(5900);
		    
		    if (connection.getSshPort() == 0)
			    connection.setSshPort(22);

            // Parse a HOST:PORT entry
		    String host = connection.getAddress();
		    if (host.indexOf(':') > -1) {
			    String p = host.substring(host.indexOf(':') + 1);
			    try {
				    connection.setPort(Integer.parseInt(p));
			    } catch (Exception e) {
			    }
			    connection.setAddress(host.substring(0, host.indexOf(':')));
	  	    }
		}

		if (connection.getConnectionType() == VncConstants.CONN_TYPE_SSH) {
			initializeSshHostKey();
		} else
			continueConnecting();
	}

	void continueConnecting () {
		// TODO: Implement left-icon
		//requestWindowFeature(Window.FEATURE_LEFT_ICON);
		//setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, R.drawable.icon); 

		setContentView(R.layout.canvas);
		vncCanvas = (VncCanvas) findViewById(R.id.vnc_canvas);
		zoomer = (ZoomControls) findViewById(R.id.zoomer);

		// Initialize and define actions for on-screen keys.
		initializeOnScreenKeys ();
	
		vncCanvas.initializeVncCanvas(connection, database, new Runnable() {
			public void run() {
				try { setModes(); } catch (NullPointerException e) { }
			}
		});
		
		vncCanvas.setOnKeyListener(this);
		vncCanvas.setFocusableInTouchMode(true);
		vncCanvas.setDrawingCacheEnabled(false);
		
		// This code detects when the soft keyboard is up and sets an appropriate visibleHeight in vncCanvas.
		// When the keyboard is gone, it resets visibleHeight and pans zero distance to prevent us from being
		// below the desktop image (if we scrolled all the way down when the keyboard was up).
		// TODO: Move this into a separate thread, and post the visibility changes to the handler.
		//       to avoid occupying the UI thread with this.
        final View rootView = ((ViewGroup)findViewById(android.R.id.content)).getChildAt(0);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                    Rect r = new Rect();

                    rootView.getWindowVisibleDisplayFrame(r);

                    // To avoid setting the visible height to a wrong value after an screen unlock event
                    // (when r.bottom holds the width of the screen rather than the height due to a rotation)
                    // we make sure r.top is zero (i.e. there is no notification bar and we are in full-screen mode)
                    // It's a bit of a hack.
                    if (r.top == 0) {
                    	if (vncCanvas.bitmapData != null) {
                        	vncCanvas.setVisibleHeight(r.bottom);
                    		vncCanvas.pan(0,0);
                    	}
                    }
                    
                    // Enable/show the zoomer if the keyboard is gone, and disable/hide otherwise.
                    // We detect the keyboard if more than 19% of the screen is covered.
                    int offset = 0;
                    int rootViewHeight = rootView.getHeight();
					if (r.bottom > rootViewHeight*0.81) {
                    	offset = rootViewHeight - r.bottom;
                        // Soft Kbd gone, shift the meta keys and arrows down.
                		if (layoutKeys != null) {
                			layoutKeys.offsetTopAndBottom(offset);
                			keyStow.offsetTopAndBottom(offset);
                			if (prevBottomOffset != offset) { 
		                		setExtraKeysVisibility(View.GONE, false);
		                		vncCanvas.invalidate();
		                		zoomer.enable();
                			}
                		}
                    } else {
                    	offset = r.bottom - rootViewHeight;
                        //  Soft Kbd up, shift the meta keys and arrows up.
                		if (layoutKeys != null) {
                			layoutKeys.offsetTopAndBottom(offset);
                			keyStow.offsetTopAndBottom(offset);
                			if (prevBottomOffset != offset) { 
		                    	setExtraKeysVisibility(View.VISIBLE, true);
		                		vncCanvas.invalidate();
		                    	zoomer.hide();
		                    	zoomer.disable();
                			}
                		}
                    }
					setKeyStowDrawableAndVisibility();
                    prevBottomOffset = offset;
             }
        });

		zoomer.hide();
		zoomer.setOnZoomInClickListener(new View.OnClickListener() {

			/*
			 * (non-Javadoc)
			 * 
			 * @see android.view.View.OnClickListener#onClick(android.view.View)
			 */
			@Override
			public void onClick(View v) {
				showZoomer(true);
				vncCanvas.scaling.zoomIn(VncCanvasActivity.this);

			}

		});
		zoomer.setOnZoomOutClickListener(new View.OnClickListener() {

			/*
			 * (non-Javadoc)
			 * 
			 * @see android.view.View.OnClickListener#onClick(android.view.View)
			 */
			@Override
			public void onClick(View v) {
				showZoomer(true);
				vncCanvas.scaling.zoomOut(VncCanvasActivity.this);

			}

		});
		zoomer.setOnZoomKeyboardClickListener(new View.OnClickListener() {

			/*
			 * (non-Javadoc)
			 * 
			 * @see android.view.View.OnClickListener#onClick(android.view.View)
			 */
			@Override
			public void onClick(View v) {
				InputMethodManager inputMgr = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
				inputMgr.toggleSoftInput(0, 0);
			}

		});
		panner = new Panner(this, vncCanvas.handler);

		inputHandler = getInputHandlerById(R.id.itemInputTouchPanZoomMouse);
	}

	
	private void setKeyStowDrawableAndVisibility() {
		Drawable replacer = null;
		if (layoutKeys.getVisibility() == View.GONE)
			replacer = getResources().getDrawable(R.drawable.showkeys);
		else
			replacer = getResources().getDrawable(R.drawable.hidekeys);
		keyStow.setBackgroundDrawable(replacer);

		if (connection.getExtraKeysToggleType() == VncConstants.EXTRA_KEYS_OFF)
			keyStow.setVisibility(View.GONE);
		else
			keyStow.setVisibility(View.VISIBLE);
	}
	
	/**
	 * Initializes the on-screen keys for meta keys and arrow keys.
	 */
	private void initializeOnScreenKeys () {
		
		layoutKeys = (RelativeLayout) findViewById(R.id.layoutKeys);

		keyStow = (ImageButton)    findViewById(R.id.keyStow);
		setKeyStowDrawableAndVisibility();
		keyStow.setOnClickListener(new OnClickListener () {
			@Override
			public void onClick(View arg0) {
				if (layoutKeys.getVisibility() == View.VISIBLE) {
					extraKeysHidden = true;
					setExtraKeysVisibility(View.GONE, false);
				} else {
					extraKeysHidden = false;
					setExtraKeysVisibility(View.VISIBLE, true);
				}
    			layoutKeys.offsetTopAndBottom(prevBottomOffset);
    			setKeyStowDrawableAndVisibility();
			}
		});

		// Define action of tab key and meta keys.
		keyTab = (ImageButton) findViewById(R.id.keyTab);
		keyTab.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = KeyEvent.KEYCODE_TAB;
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyTab.setImageResource(R.drawable.tabon);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyTab.setImageResource(R.drawable.taboff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});

		keyEsc = (ImageButton) findViewById(R.id.keyEsc);
		keyEsc.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = 111; /* KEYCODE_ESCAPE */
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyEsc.setImageResource(R.drawable.escon);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyEsc.setImageResource(R.drawable.escoff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});

		keyCtrl = (ImageButton) findViewById(R.id.keyCtrl);
		keyCtrl.setOnClickListener(new OnClickListener () {
			@Override
			public void onClick(View arg0) {
				boolean on = vncCanvas.getKeyboard().onScreenCtrlToggle();
				keyCtrlToggled = false;
				if (on)
					keyCtrl.setImageResource(R.drawable.ctrlon);
				else
					keyCtrl.setImageResource(R.drawable.ctrloff);
			}
		});
		
		keyCtrl.setOnLongClickListener(new OnLongClickListener () {
			@Override
			public boolean onLongClick(View arg0) {
				BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
				boolean on = vncCanvas.getKeyboard().onScreenCtrlToggle();
				keyCtrlToggled = true;
				if (on)
					keyCtrl.setImageResource(R.drawable.ctrlon);
				else
					keyCtrl.setImageResource(R.drawable.ctrloff);
				return true;
			}
		});

		keySuper = (ImageButton) findViewById(R.id.keySuper);
		keySuper.setOnClickListener(new OnClickListener () {
			@Override
			public void onClick(View arg0) {
				boolean on = vncCanvas.getKeyboard().onScreenSuperToggle();
				keySuperToggled = false;
				if (on)
					keySuper.setImageResource(R.drawable.superon);
				else
					keySuper.setImageResource(R.drawable.superoff);
			}
		});

		keySuper.setOnLongClickListener(new OnLongClickListener () {
			@Override
			public boolean onLongClick(View arg0) {
				BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
				boolean on = vncCanvas.getKeyboard().onScreenSuperToggle();
				keySuperToggled = true;
				if (on)
					keySuper.setImageResource(R.drawable.superon);
				else
					keySuper.setImageResource(R.drawable.superoff);
				return true;
			}
		});

		keyAlt = (ImageButton) findViewById(R.id.keyAlt);
		keyAlt.setOnClickListener(new OnClickListener () {
			@Override
			public void onClick(View arg0) {
				boolean on = vncCanvas.getKeyboard().onScreenAltToggle();
				keyAltToggled = false;
				if (on)
					keyAlt.setImageResource(R.drawable.alton);
				else
					keyAlt.setImageResource(R.drawable.altoff);
			}
		});
		
		keyAlt.setOnLongClickListener(new OnLongClickListener () {
			@Override
			public boolean onLongClick(View arg0) {
				BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
				boolean on = vncCanvas.getKeyboard().onScreenAltToggle();
				keyAltToggled = true;
				if (on)
					keyAlt.setImageResource(R.drawable.alton);
				else
					keyAlt.setImageResource(R.drawable.altoff);
				return true;
			}
		});

		// TODO: Evaluate whether I should instead be using:
		// vncCanvas.sendMetaKey(MetaKeyBean.keyArrowLeft);

		// Define action of arrow keys.
		keyUp = (ImageButton) findViewById(R.id.keyUpArrow);
		keyUp.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = KeyEvent.KEYCODE_DPAD_UP;
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyUp.setImageResource(R.drawable.upon);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyUp.setImageResource(R.drawable.upoff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});

		keyDown = (ImageButton) findViewById(R.id.keyDownArrow);
		keyDown.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = KeyEvent.KEYCODE_DPAD_DOWN;
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyDown.setImageResource(R.drawable.downon);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyDown.setImageResource(R.drawable.downoff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});

		keyLeft = (ImageButton) findViewById(R.id.keyLeftArrow);
		keyLeft.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = KeyEvent.KEYCODE_DPAD_LEFT;
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyLeft.setImageResource(R.drawable.lefton);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyLeft.setImageResource(R.drawable.leftoff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});

		keyRight = (ImageButton) findViewById(R.id.keyRightArrow);
		keyRight.setOnTouchListener(new OnTouchListener () {
			@Override
			public boolean onTouch(View arg0, MotionEvent e) {
				RemoteKeyboard k = vncCanvas.getKeyboard();
				int key = KeyEvent.KEYCODE_DPAD_RIGHT;
				if (e.getAction() == MotionEvent.ACTION_DOWN) {
					BCFactory.getInstance().getBCHaptic().performLongPressHaptic(vncCanvas);
					keyRight.setImageResource(R.drawable.righton);
					k.repeatKeyEvent(key, new KeyEvent(e.getAction(), key));
					return true;	
				} else if (e.getAction() == MotionEvent.ACTION_UP) {
					keyRight.setImageResource(R.drawable.rightoff);
					resetOnScreenKeys (0);
					k.stopRepeatingKeyEvent();
					return true;
				}
				return false;
			}
		});
	}

	/**
	 * Resets the state and image of the on-screen keys.
	 */
	private void resetOnScreenKeys (int keyCode) {
		// Do not reset on-screen keys if keycode is SHIFT.
		switch (keyCode) {
		case KeyEvent.KEYCODE_SHIFT_LEFT:
		case KeyEvent.KEYCODE_SHIFT_RIGHT: return;
		}
		if (!keyCtrlToggled) {
			keyCtrl.setImageResource(R.drawable.ctrloff);
			vncCanvas.getKeyboard().onScreenCtrlOff();
		}
		if (!keyAltToggled) {
			keyAlt.setImageResource(R.drawable.altoff);
			vncCanvas.getKeyboard().onScreenAltOff();
		}
		if (!keySuperToggled) {
			keySuper.setImageResource(R.drawable.superoff);
			vncCanvas.getKeyboard().onScreenSuperOff();
		}
	}

	
	/**
	 * Sets the visibility of the extra keys appropriately.
	 */
	private void setExtraKeysVisibility (int visibility, boolean forceVisible) {
		Configuration config = getResources().getConfiguration();
		//Log.e(TAG, "Hardware kbd hidden: " + Integer.toString(config.hardKeyboardHidden));
		//Log.e(TAG, "Any keyboard hidden: " + Integer.toString(config.keyboardHidden));
		//Log.e(TAG, "Keyboard type: " + Integer.toString(config.keyboard));

		boolean makeVisible = forceVisible;
		if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
			makeVisible = true;

		if (!extraKeysHidden && makeVisible && 
			connection.getExtraKeysToggleType() == VncConstants.EXTRA_KEYS_ON) {
			layoutKeys.setVisibility(View.VISIBLE);
			layoutKeys.invalidate();
			return;
		}
		
		if (visibility == View.GONE) {
			layoutKeys.setVisibility(View.GONE);
			layoutKeys.invalidate();
		}
	}
	
	/*
	 * TODO: REMOVE THIS AS SOON AS POSSIBLE.
	 * onPause: This is an ugly hack for the Playbook, because the Playbook hides the keyboard upon unlock.
	 * This causes the visible height to remain less, as if the soft keyboard is still up. This hack must go 
	 * away as soon as the Playbook doesn't need it anymore.
	 */
	@Override
	protected void onPause(){
		super.onPause();
		try {
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(vncCanvas.getWindowToken(), 0);
		} catch (NullPointerException e) { }
	}

	/*
	 * TODO: REMOVE THIS AS SOON AS POSSIBLE.
	 * onResume: This is an ugly hack for the Playbook which hides the keyboard upon unlock. This causes the visible
	 * height to remain less, as if the soft keyboard is still up. This hack must go away as soon
	 * as the Playbook doesn't need it anymore.
	 */
	@Override
	protected void onResume(){
		super.onResume();
		Log.i(TAG, "onResume called.");
		try {
			vncCanvas.postInvalidateDelayed(600);
		} catch (NullPointerException e) { }
	}
	
	/**
	 * Set modes on start to match what is specified in the ConnectionBean;
	 * color mode (already done) scaling, input mode
	 */
	void setModes() {
		AbstractInputHandler handler = getInputHandlerByName(connection.getInputMode());
		AbstractScaling.getByScaleType(connection.getScaleMode()).setScaleTypeForActivity(this);
		this.inputHandler = handler;
		showPanningState(false);
	}

	ConnectionBean getConnection() {
		return connection;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreateDialog(int)
	 */
	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case R.layout.entertext:
			return new EnterTextDialog(this);
		case R.id.itemHelpInputMode:
			return createHelpDialog ();
		}
		
		// Default to meta key dialog
		return new MetaKeyDialog(this);
	}

	/**
	 * Creates the help dialog for this activity.
	 */
	private Dialog createHelpDialog() {
	    AlertDialog.Builder adb = new AlertDialog.Builder(this)
	    		.setMessage(R.string.input_mode_help_text)
	    		.setPositiveButton(R.string.close,
	    				new DialogInterface.OnClickListener() {
	    					public void onClick(DialogInterface dialog,
	    							int whichButton) {
	    						// We don't have to do anything.
	    					}
	    				});
	    Dialog d = adb.setView(new ListView (this)).create();
	    WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
	    lp.copyFrom(d.getWindow().getAttributes());
	    lp.width = WindowManager.LayoutParams.FILL_PARENT;
	    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
	    d.show();
	    d.getWindow().setAttributes(lp);
	    return d;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onPrepareDialog(int, android.app.Dialog)
	 */
	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		super.onPrepareDialog(id, dialog);
		if (dialog instanceof ConnectionSettable)
			((ConnectionSettable) dialog).setConnection(connection);
	}

	/**
	 * This runnable fixes things up after a rotation.
	 */
	private Runnable rotationCorrector = new Runnable() {
		public void run() {
			try { correctAfterRotation (); } catch (NullPointerException e) { }
		}
	};

	/**
	 * This function is called by the rotationCorrector runnable
	 * to fix things up after a rotation.
	 */
	private void correctAfterRotation () {
		// Its quite common to see NullPointerExceptions here when this function is called
		// at the point of disconnection. Hence, we catch and ignore the error.
		float oldScale = vncCanvas.scaling.getScale();
		int x = vncCanvas.absoluteXPosition;
		int y = vncCanvas.absoluteYPosition;
		vncCanvas.scaling.setScaleTypeForActivity(VncCanvasActivity.this);
		float newScale = vncCanvas.scaling.getScale();
		vncCanvas.scaling.adjust(this, oldScale/newScale, 0, 0);
		newScale = vncCanvas.scaling.getScale();
		if (newScale <= oldScale) {
			vncCanvas.absoluteXPosition = x;
			vncCanvas.absoluteYPosition = y;
			vncCanvas.scrollToAbsolute();
		}
	}


	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		
		try {
			setExtraKeysVisibility(View.GONE, false);
			
			// Correct a few times just in case. There is no visual effect.
			handler.postDelayed(rotationCorrector, 300);
			handler.postDelayed(rotationCorrector, 600);
			handler.postDelayed(rotationCorrector, 1200);
		} catch (NullPointerException e) { }
	}

	@Override
	protected void onStart() {
		super.onStart();
		try {
			vncCanvas.postInvalidateDelayed(800);
		} catch (NullPointerException e) { }
	}

	@Override
	protected void onStop() {
		super.onStop();
	}

	@Override
	protected void onRestart() {
		super.onRestart();
		try {
			vncCanvas.postInvalidateDelayed(1000);
		} catch (NullPointerException e) { }
	}

	/** {@inheritDoc} */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		try {
			getMenuInflater().inflate(R.menu.vnccanvasactivitymenu, menu);

			menu.findItem(vncCanvas.scaling.getId()).setChecked(true);
	
			Menu inputMenu = menu.findItem(R.id.itemInputMode).getSubMenu();
			inputModeMenuItems = new MenuItem[inputModeIds.length];
			for (int i = 0; i < inputModeIds.length; i++) {
				inputModeMenuItems[i] = inputMenu.findItem(inputModeIds[i]);
			}
			updateInputMenu();
			
			Menu scalingMenu = menu.findItem(R.id.itemScaling).getSubMenu();
			scalingModeMenuItems = new MenuItem[scalingModeIds.length];
			for (int i = 0; i < scalingModeIds.length; i++) {
				scalingModeMenuItems[i] = scalingMenu.findItem(scalingModeIds[i]);
			}
			updateScalingMenu();
			
			// Set the text of the Extra Keys menu item appropriately.
			if (connection.getExtraKeysToggleType() == VncConstants.EXTRA_KEYS_ON)
				menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_disable);
			else
				menu.findItem(R.id.itemExtraKeys).setTitle(R.string.extra_keys_enable);
			
	/*		menu.findItem(R.id.itemFollowMouse).setChecked(
					connection.getFollowMouse());
			menu.findItem(R.id.itemFollowPan).setChecked(connection.getFollowPan());
	 */
	/* TODO: This is how one detects long-presses on menu items. However, getActionView is not available in Android 2.3...
			menu.findItem(R.id.itemExtraKeys).getActionView().setOnLongClickListener(new OnLongClickListener () {
	
				@Override
				public boolean onLongClick(View arg0) {
					Toast.makeText(arg0.getContext(), "Long Press Detected.", Toast.LENGTH_LONG).show();
					return false;
				}
				
			});
	*/
		} catch (NullPointerException e) { }
		return true;
	}

	/**
	 * Change the scaling mode sub-menu to reflect available scaling modes.
	 */
	void updateScalingMenu() {
		try {
			for (MenuItem item : scalingModeMenuItems) {
				// If the entire framebuffer is NOT contained in the bitmap, fit-to-screen is meaningless.
				if (item.getItemId() == R.id.itemFitToScreen) {
					if ( vncCanvas != null && vncCanvas.bitmapData != null &&
						 (vncCanvas.bitmapData.bitmapheight != vncCanvas.bitmapData.framebufferheight ||
						  vncCanvas.bitmapData.bitmapwidth  != vncCanvas.bitmapData.framebufferwidth) )
						item.setEnabled(false);
					else
						item.setEnabled(true);
				} else
					item.setEnabled(true);
			}
		} catch (NullPointerException e) { }
	}	
	
	/**
	 * Change the input mode sub-menu to reflect change in scaling
	 */
	void updateInputMenu() {
		try {
			for (MenuItem item : inputModeMenuItems) {
				item.setEnabled(vncCanvas.scaling.isValidInputMode(item.getItemId()));
				if (getInputHandlerById(item.getItemId()) == inputHandler)
					item.setChecked(true);
			}
		} catch (NullPointerException e) { }
	}

	/**
	 * If id represents an input handler, return that; otherwise return null
	 * 
	 * @param id
	 * @return
	 */
	AbstractInputHandler getInputHandlerById(int id) {
		if (inputModeHandlers == null) {
			inputModeHandlers = new AbstractInputHandler[inputModeIds.length];
		}
		for (int i = 0; i < inputModeIds.length; ++i) {
			if (inputModeIds[i] == id) {
				if (inputModeHandlers[i] == null) {
					switch (id) {
/*					case R.id.itemInputFitToScreen:
						inputModeHandlers[i] = new FitToScreenMode();
						break;
					case R.id.itemInputPan:
						inputModeHandlers[i] = new PanMode();
						break;
					case R.id.itemInputTouchPanTrackballMouse:
						inputModeHandlers[i] = new TouchPanTrackballMouse();
						break;
					case R.id.itemInputMouse:
						inputModeHandlers[i] = new MouseMode();
						break; 

					case R.id.itemInputDPadPanTouchMouse:
						inputModeHandlers[i] = new DPadPanTouchMouseMode();
						break;
 */					
					case R.id.itemInputTouchPanZoomMouse:
						inputModeHandlers[i] = new TouchMouseSwipePanInputHandler(this, vncCanvas);
						break;
					case R.id.itemInputDragPanZoomMouse:
						inputModeHandlers[i] = new TouchMouseDragPanInputHandler(this, vncCanvas);
						break;
					case R.id.itemInputTouchpad:
						inputModeHandlers[i] = new SimulatedTouchpadInputHandler(this, vncCanvas);
						break;
					case R.id.itemInputSingleHanded:
						inputModeHandlers[i] = new SingleHandedInputHandler(this, vncCanvas);
						break;

					}
				}
				return inputModeHandlers[i];
			}
		}
		return null;
	}

	void clearInputHandlers() {
		if (inputModeHandlers == null)
			return;

		for (int i = 0; i < inputModeIds.length; ++i) {
			inputModeHandlers[i] = null;
		}
		inputModeHandlers = null;
	}
	
	AbstractInputHandler getInputHandlerByName(String name) {
		AbstractInputHandler result = null;
		for (int id : inputModeIds) {
			AbstractInputHandler handler = getInputHandlerById(id);
			if (handler.getName().equals(name)) {
				result = handler;
				break;
			}
		}
		if (result == null) {
			result = getInputHandlerById(R.id.itemInputTouchPanZoomMouse);
		}
		return result;
	}
	
	int getModeIdFromHandler(AbstractInputHandler handler) {
		for (int id : inputModeIds) {
			if (handler == getInputHandlerById(id))
				return id;
		}
		return R.id.itemInputTouchPanZoomMouse;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		vncCanvas.getKeyboard().setAfterMenu(true);
		switch (item.getItemId()) {
		case R.id.itemInfo:
			vncCanvas.showConnectionInfo();
			return true;
		case R.id.itemSpecialKeys:
			showDialog(R.layout.metakey);
			return true;
		case R.id.itemColorMode:
			selectColorModel();
			return true;
			// Following sets one of the scaling options
		case R.id.itemZoomable:
		case R.id.itemOneToOne:
		case R.id.itemFitToScreen:
			AbstractScaling.getById(item.getItemId()).setScaleTypeForActivity(this);
			item.setChecked(true);
			showPanningState(false);
			return true;
		case R.id.itemCenterMouse:
			vncCanvas.getPointer().warpMouse(vncCanvas.absoluteXPosition + vncCanvas.getVisibleWidth()  / 2,
											 vncCanvas.absoluteYPosition + vncCanvas.getVisibleHeight() / 2);
			return true;
		case R.id.itemDisconnect:
			vncCanvas.closeConnection();
			finish();
			return true;
		case R.id.itemEnterText:
			showDialog(R.layout.entertext);
			return true;
		case R.id.itemCtrlAltDel:
			vncCanvas.getKeyboard().sendMetaKey(MetaKeyBean.keyCtrlAltDel);
			return true;
/*		case R.id.itemFollowMouse:
			boolean newFollow = !connection.getFollowMouse();
			item.setChecked(newFollow);
			connection.setFollowMouse(newFollow);
			if (newFollow) {
				vncCanvas.panToMouse();
			}
			connection.save(database.getWritableDatabase());
			database.close();
			return true;
		case R.id.itemFollowPan:
			boolean newFollowPan = !connection.getFollowPan();
			item.setChecked(newFollowPan);
			connection.setFollowPan(newFollowPan);
			connection.save(database.getWritableDatabase());
			database.close();
			return true;
 
		case R.id.itemArrowLeft:
			vncCanvas.sendMetaKey(MetaKeyBean.keyArrowLeft);
			return true;
		case R.id.itemArrowUp:
			vncCanvas.sendMetaKey(MetaKeyBean.keyArrowUp);
			return true;
		case R.id.itemArrowRight:
			vncCanvas.sendMetaKey(MetaKeyBean.keyArrowRight);
			return true;
		case R.id.itemArrowDown:
			vncCanvas.sendMetaKey(MetaKeyBean.keyArrowDown);
			return true;
*/
		case R.id.itemSendKeyAgain:
			sendSpecialKeyAgain();
			return true;
		// Disabling Manual/Wiki Menu item as the original does not correspond to this project anymore.
		//case R.id.itemOpenDoc:
		//	Utils.showDocumentation(this);
		//	return true;
		case R.id.itemExtraKeys:
			if (connection.getExtraKeysToggleType() == VncConstants.EXTRA_KEYS_ON) {
				connection.setExtraKeysToggleType(VncConstants.EXTRA_KEYS_OFF);
				item.setTitle(R.string.extra_keys_enable);
				setExtraKeysVisibility(View.GONE, false);
			} else {
				connection.setExtraKeysToggleType(VncConstants.EXTRA_KEYS_ON);
				item.setTitle(R.string.extra_keys_disable);
				setExtraKeysVisibility(View.VISIBLE, false);
				extraKeysHidden = false;
			}
			setKeyStowDrawableAndVisibility();
			connection.save(database.getWritableDatabase());
			database.close();
			return true;
		case R.id.itemHelpInputMode:
			showDialog(R.id.itemHelpInputMode);
			return true;
		default:
			AbstractInputHandler input = getInputHandlerById(item.getItemId());
			if (input != null) {
				inputHandler = input;
				connection.setInputMode(input.getName());
				if (input.getName().equals(SimulatedTouchpadInputHandler.TOUCHPAD_MODE)) {
					connection.setFollowMouse(true);
					connection.setFollowPan(true);
				} else {
					connection.setFollowMouse(false);
					connection.setFollowPan(false);
				}

				item.setChecked(true);
				showPanningState(true);
				connection.save(database.getWritableDatabase());
    			database.close();
				return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	private MetaKeyBean lastSentKey;

	private void sendSpecialKeyAgain() {
		if (lastSentKey == null
				|| lastSentKey.get_Id() != connection.getLastMetaKeyId()) {
			ArrayList<MetaKeyBean> keys = new ArrayList<MetaKeyBean>();
			Cursor c = database.getReadableDatabase().rawQuery(
					MessageFormat.format("SELECT * FROM {0} WHERE {1} = {2}",
							MetaKeyBean.GEN_TABLE_NAME,
							MetaKeyBean.GEN_FIELD__ID, connection
									.getLastMetaKeyId()),
					MetaKeyDialog.EMPTY_ARGS);
			MetaKeyBean.Gen_populateFromCursor(c, keys, MetaKeyBean.NEW);
			c.close();
			if (keys.size() > 0) {
				lastSentKey = keys.get(0);
			} else {
				lastSentKey = null;
			}
		}
		if (lastSentKey != null)
			vncCanvas.getKeyboard().sendMetaKey(lastSentKey);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (vncCanvas != null)
			vncCanvas.closeConnection();
		if (database != null)
			database.close();
		vncCanvas = null;
		connection = null;
		database = null;
		zoomer = null;
		panner = null;
		clearInputHandlers();
		inputHandler = null;
		System.gc();
	}

	@Override
	public boolean onKey(View v, int keyCode, KeyEvent evt) {

		boolean consumed = false;

		if (keyCode == KeyEvent.KEYCODE_MENU) {
			if (evt.getAction() == KeyEvent.ACTION_DOWN)
				return super.onKeyDown(keyCode, evt);
			else
				return super.onKeyUp(keyCode, evt);
		}

		try {
			if (evt.getAction() == KeyEvent.ACTION_DOWN || evt.getAction() == KeyEvent.ACTION_MULTIPLE) {
				consumed = inputHandler.onKeyDown(keyCode, evt);
			} else if (evt.getAction() == KeyEvent.ACTION_UP){
				consumed = inputHandler.onKeyUp(keyCode, evt);
			}
			resetOnScreenKeys (keyCode);
		} catch (NullPointerException e) { }

		return consumed;
	}

	public void showPanningState(boolean showLonger) {
		if (showLonger) {
			final Toast t = Toast.makeText(this, inputHandler.getHandlerDescription(), Toast.LENGTH_LONG);
			TimerTask tt = new TimerTask () {
				@Override
				public void run() {
					t.show();
					try { Thread.sleep(2000); } catch (InterruptedException e) { }
					t.show();
				}};
			new Timer ().schedule(tt, 2000);
			t.show();
		} else {
			Toast t = Toast.makeText(this, inputHandler.getHandlerDescription(), Toast.LENGTH_SHORT);
			t.show();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onTrackballEvent(android.view.MotionEvent)
	 */
	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		try {
			// If we are using the Dpad as arrow keys, don't send the event to the inputHandler.
			if (connection.getUseDpadAsArrows())
				return false;
			return inputHandler.onTrackballEvent(event);
		} catch (NullPointerException e) { }
		return false;
	}

	// Send touch events or mouse events like button clicks to be handled.
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		try {
			return inputHandler.onTouchEvent(event);
		} catch (NullPointerException e) { }
		return false;
	}

	// Send e.g. mouse events like hover and scroll to be handled.
	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		try {
			return inputHandler.onTouchEvent(event);
		} catch (NullPointerException e) { }
		return false;
	}

	private void selectColorModel() {

		String[] choices = new String[COLORMODEL.values().length];
		int currentSelection = -1;
		for (int i = 0; i < choices.length; i++) {
			COLORMODEL cm = COLORMODEL.values()[i];
			choices[i] = cm.toString();
			if (vncCanvas.isColorModel(cm))
				currentSelection = i;
		}

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		ListView list = new ListView(this);
		list.setAdapter(new ArrayAdapter<String>(this,
				android.R.layout.simple_list_item_checked, choices));
		list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
		list.setItemChecked(currentSelection, true);
		list.setOnItemClickListener(new OnItemClickListener() {
			public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
				dialog.dismiss();
				COLORMODEL cm = COLORMODEL.values()[arg2];
				vncCanvas.setColorModel(cm);
				connection.setColorModel(cm.nameString());
				connection.save(database.getWritableDatabase());
    			database.close();
				Toast.makeText(VncCanvasActivity.this, "Updating Color Model to " + cm.toString(), Toast.LENGTH_SHORT).show();
			}
		});
		dialog.setContentView(list);
		dialog.show();
	}

	// Returns whether we are using D-pad/Trackball to send arrow key events.
	boolean getUseDpadAsArrows() {
		return connection.getUseDpadAsArrows();
	}

	// Returns whether the D-pad should be rotated to accommodate BT keyboards paired with phones.
	boolean getRotateDpad() {
		return connection.getRotateDpad();
	}
	
	// Returns whether the D-pad should be rotated to accommodate BT keyboards paired with phones.
	float getSensitivity() {
		// TODO: Make this a slider config option.
		return 2.0f;
	}
	
	boolean getAccelerationEnabled() {
		// TODO: Make this a config option.
		return true;
	}

	long hideZoomAfterMs;
	static final long ZOOM_HIDE_DELAY_MS = 2500;
	HideZoomRunnable hideZoomInstance = new HideZoomRunnable();

	public void stopPanner() {
		panner.stop ();
	}
	
	public void showZoomer(boolean force) {
		if (force || zoomer.getVisibility() != View.VISIBLE) {
			zoomer.show();
			hideZoomAfterMs = SystemClock.uptimeMillis() + ZOOM_HIDE_DELAY_MS;
			vncCanvas.handler.postAtTime(hideZoomInstance, hideZoomAfterMs + 10);
		}
	}

	private class HideZoomRunnable implements Runnable {
		public void run() {
			if (SystemClock.uptimeMillis() >= hideZoomAfterMs) {
				zoomer.hide();
			}
		}
	}
}
