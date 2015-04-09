package com.example.wificastapp;

import android.support.v7.app.ActionBarActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

public class Sender extends ActionBarActivity {
	
	private static final int REQUEST_PATH = 1;
    String curFileName, curFilePath;    
    EditText edittext;
    TextView filePath;
    
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sender);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		Intent intent = getIntent();
		String username = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);
		
	    
	    edittext = (EditText)findViewById(R.id.editText);

	}
	
	

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.sender, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
    public void getfile(View view){
        Intent intent1 = new Intent(this, FileChooser.class);
        startActivityForResult(intent1,REQUEST_PATH);
    }
    
    public void startListeningConnection(View view){
    	Intent intent = new Intent(this, SenderConnection.class);
        intent.putExtra("FilePath", curFilePath);
        intent.putExtra("FileName", curFileName);
	    startActivity(intent);
    }
    
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        // See which child activity is calling us back.
        if (requestCode == REQUEST_PATH){
                if (resultCode == RESULT_OK) {
                        curFileName = data.getStringExtra("GetFileName");
                        curFilePath = data.getStringExtra("GetPath");
                        edittext.setText(curFileName);
//                        TextView filePath = (TextView) findViewById(R.id.textView1);
//                        filePath.setText(curFilePath);
                        
                        
                }
         }
    }
}
