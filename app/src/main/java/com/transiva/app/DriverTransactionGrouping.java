package com.transiva.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Presentation only. Never changes or removes wallet ledger records. */
final class DriverTransactionGrouping {
    static final class Entry {
        final boolean order;
        final String type, reference;
        final String[] aliases;
        Entry(boolean order, String type, String reference, String... aliases) {
            this.order=order; this.type=clean(type).toLowerCase(Locale.US);
            this.reference=clean(reference); this.aliases=aliases;
        }
    }
    static final class Group {
        final int primary;
        final List<Integer> members=new ArrayList<>();
        Group(int primary) { this.primary=primary; }
    }
    static List<Group> group(List<Entry> entries) {
        Map<String,Integer> owners=new LinkedHashMap<>();
        Map<Integer,Group> orders=new LinkedHashMap<>();
        for(int i=0;i<entries.size();i++) {
            Entry e=entries.get(i);
            if(!e.order)continue;
            orders.put(i,new Group(i));
            for(String raw:e.aliases) {
                String alias=clean(raw);
                if(alias.isEmpty())continue;
                Integer old=owners.get(alias);
                owners.put(alias,old==null||old==i?i:-1); // Ambiguous aliases never merge.
            }
        }
        Map<String,Group> walletGroups=new LinkedHashMap<>();
        Map<Integer,Group> placement=new LinkedHashMap<>();
        for(int i=0;i<entries.size();i++) {
            Entry e=entries.get(i);
            if(e.order){placement.put(i,orders.get(i));continue;}
            String key=orderKey(e.type,e.reference);
            Integer owner=key.isEmpty()?null:owners.get(key);
            if(owner!=null&&owner>=0) {
                Group g=orders.get(owner);g.members.add(i);placement.put(i,g);
            } else if(!key.isEmpty() && (owner==null)) {
                Group g=walletGroups.get(key);
                if(g==null){g=new Group(i);walletGroups.put(key,g);}
                g.members.add(i);placement.put(i,g);
            } else {Group g=new Group(i);g.members.add(i);placement.put(i,g);}
        }
        List<Group> result=new ArrayList<>();
        for(Group g:placement.values())if(!result.contains(g))result.add(g);
        return result;
    }
    static String orderKey(String type,String reference) {
        String t=clean(type).toLowerCase(Locale.US),r=clean(reference);
        String prefix="app_fee".equals(t)?"FEE-":"escrow_release".equals(t)?"RELEASE-":"promo_subsidy".equals(t)?"PROMO-":"";
        if(prefix.isEmpty()||!r.startsWith(prefix))return "";
        r=r.substring(prefix.length());
        // These are exact server namespaces, not guesses from dates or amounts.
        if(r.startsWith("ORD-")||r.startsWith("FOOD-")||r.startsWith("SHOP-")||r.startsWith("SEND-"))return r;
        return "";
    }
    static String shortReference(String reference) {
        String r=clean(reference);
        return r.length()>26?r.substring(0,14)+"…"+r.substring(r.length()-8):r;
    }
    private static String clean(String s) {return s==null||"null".equalsIgnoreCase(s.trim())?"":s.trim();}
}
