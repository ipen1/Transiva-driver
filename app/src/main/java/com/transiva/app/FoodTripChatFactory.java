package com.transiva.app;

import android.content.Intent;
import android.graphics.PointF;
import android.widget.FrameLayout;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** One preview queue for both rooms, anchored to the correct map location. */
final class FoodTripChatFactory {
    static TripMapChatPreview trip(DriverTripActivity host,FrameLayout page,Supplier<JSONObject> order,
                                   IntSupplier top,IntSupplier bottom){
        Supplier<PointF> restaurant=()->googlePoint(host,true);
        TripMapChatPreview preview=new TripMapChatPreview(host,page,order,
                ()->googlePoint(host,DeliveryTripPresentation.customerAtPickup(order.get())),top,bottom,
                ()->{if(host.communicationController!=null)host.communicationController.openCustomerChat();});
        if(FoodTripPresentation.isFood(order.get()))preview.merchant(restaurant,
                ()->{if(host.communicationController!=null)host.communicationController.openMerchantChat();});
        return preview;
    }
    private static PointF googlePoint(DriverTripActivity host,boolean restaurant){
        if(host.googleMap==null)return null;
        double lat=NavigationTargetPolicy.coord(host.order,restaurant?"pickup_lat":"delivery_lat",restaurant?"user_lat":"destination_lat");
        double lng=NavigationTargetPolicy.coord(host.order,restaurant?"pickup_lng":"delivery_lng",restaurant?"user_lng":"destination_lng");
        if(!NavigationTargetPolicy.valid(lat,lng))return null;
        android.graphics.Point point=host.googleMap.getProjection().toScreenLocation(new com.google.android.gms.maps.model.LatLng(lat,lng));
        return new PointF(point.x,point.y);
    }
    static TripMapChatPreview navigation(DriverNavigationActivity host,FrameLayout page,IntSupplier top,IntSupplier bottom){
        TripMapChatPreview preview=new TripMapChatPreview(host,page,()->host.order,
                ()->nativePoint(host,DeliveryTripPresentation.customerAtPickup(host.order)),top,bottom,
                ()->{if(host.communicationController!=null)host.communicationController.openChat();});
        if(FoodTripPresentation.isFood(host.order))preview.merchant(()->nativePoint(host,true),()->{
            Intent intent=new Intent(host,DriverMerchantChatActivity.class);
            intent.putExtra("order_id",NavigationTargetPolicy.id(host.order));
            intent.putExtra("order_db_id",host.order.optString("id"));
            intent.putExtra("merchant_name",FoodTripPresentation.restaurant(host.order));host.startActivity(intent);
        });
        return preview;
    }
    private static PointF nativePoint(DriverNavigationActivity host,boolean restaurant){
        if(host.map==null||!host.styleReady||host.inPictureInPicture)return null;
        double lat=NavigationTargetPolicy.coord(host.order,restaurant?"pickup_lat":"delivery_lat",restaurant?"user_lat":"destination_lat");
        double lng=NavigationTargetPolicy.coord(host.order,restaurant?"pickup_lng":"delivery_lng",restaurant?"user_lng":"destination_lng");
        return NavigationTargetPolicy.valid(lat,lng)?host.map.getProjection().toScreenLocation(new org.maplibre.android.geometry.LatLng(lat,lng)):null;
    }
}
