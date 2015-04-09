package com.example.wificastapp;

import java.util.List;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

public class SinkArrayAdapter extends ArrayAdapter<SinkStats>{
	private Context c;
    private int id;
    private List<SinkStats> items;
    
    public SinkArrayAdapter(Context context, int textViewResourceId,
            List<SinkStats> objects) {
    super(context, textViewResourceId, objects);
    c = context;
    id = textViewResourceId;
    items = objects;
    
    }
    public SinkStats getItem(int i)
    {
            return items.get(i);
    }
    
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                LayoutInflater vi = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                v = vi.inflate(id, null);
            }
           
            /* create a new view of my layout and inflate it in the row */
             //convertView = ( RelativeLayout ) inflater.inflate( resource, null );
            
            final SinkStats o = items.get(position);
            if (o != null) {
                    TextView t1 = (TextView) v.findViewById(R.id.username);
                    TextView t2 = (TextView) v.findViewById(R.id.userIP);
                    TextView t3 = (TextView) v.findViewById(R.id.lostPackets);
                    
                    if(t1!=null)
                 	   t1.setText(o.getName());
                    if(t2!=null)
                        t2.setText(o.getIP().getHostAddress());
                    if(t3!=null)
                         t3.setText(Integer.toString(o.getLostCount()));
            }
            return v;
    }
}
