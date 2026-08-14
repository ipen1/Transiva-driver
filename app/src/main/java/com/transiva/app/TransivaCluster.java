package com.transiva.app;

public final class TransivaCluster {
    public static final class Item {
        public final int id; public final String code; public final String name; public final double lat; public final double lng;
        Item(int id,String code,String name,double lat,double lng){this.id=id;this.code=code;this.name=name;this.lat=lat;this.lng=lng;}
    }
    public static final Item[] ALL = new Item[]{
            new Item(1,"SUMBERSARI","Sumbersari",-0.9291806,120.2289806),
            new Item(2,"DOLAGO_RIBAMBA","Dolago / Ribamba",-0.8748000,120.2040000),
            new Item(3,"PARIGI","Parigi",-0.8024100,120.1710800),
            new Item(4,"PANGI","Pangi",-0.7416889,120.0681194),
            new Item(5,"TOBOLI","Toboli",-0.6999500,120.0805500)
    };
    private TransivaCluster(){}
    public static Item nearest(double lat,double lng){
        Item best=ALL[0]; double bestKm=Double.MAX_VALUE;
        for(Item item:ALL){ double km=distanceKm(lat,lng,item.lat,item.lng); if(km<bestKm){bestKm=km;best=item;} }
        return best;
    }
    public static double distanceKm(double lat1,double lng1,double lat2,double lng2){
        double r=6371.0,dLat=Math.toRadians(lat2-lat1),dLng=Math.toRadians(lng2-lng1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
        return r*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(1e-12,1-a)));
    }
}
