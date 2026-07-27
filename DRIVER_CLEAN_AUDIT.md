# Driver Clean Audit

- `applicationId` = `com.transiva.driver`
- No Java source files whose names start with `Admin`, `Customer`, or `Merchant`.
- `app/src/main/java/com/transiva/app/customer/` removed.
- Customer/merchant/admin activities removed from AndroidManifest.
- Driver login rejects non-driver roles before session routing.
- Splash rejects an existing non-driver session.
- PIN routes only to DriverDashboardActivity.
- Firebase notification navigation routes only to Driver screens.
- Former customer-named shared chat helpers were renamed:
  - `CustomerMessageApi` -> `DriverChatMediaApi`
  - `CustomerMessageStatus` -> `DriverMessageStatus`
- Release signing uses GitHub Secrets and reconstructs the keystore only inside CI.
