package com.transiva.app;

/** Missing financial fields are unknown, never an implicit zero commission. */
final class DriverIncomeSummary {
    static final class Result {
        final boolean known;
        final double net;
        final double fee;
        Result(boolean known,double net,double fee) { this.known=known;this.net=net;this.fee=fee; }
    }
    private static boolean valid(double n) { return !Double.isNaN(n)&&!Double.isInfinite(n)&&n>=0; }
    static Result resolve(double gross,double explicitNet,double appFee,double totalDeductions) {
        double cuts=valid(totalDeductions)?totalDeductions:appFee;
        double displayedFee=valid(appFee)?appFee:(valid(cuts)?cuts:0);
        if(valid(explicitNet))return new Result(true,explicitNet,displayedFee);
        if(valid(gross)&&valid(cuts))return new Result(true,Math.max(0,gross-cuts),displayedFee);
        return new Result(false,0,displayedFee);
    }
    static boolean isAppFee(String type,String reference) {
        if("app_fee".equalsIgnoreCase(type))return true;
        String r=reference==null?"":reference.toUpperCase(java.util.Locale.US);
        return r.startsWith("FEE-ORD")||r.startsWith("FEE-SEND-");
    }
}
