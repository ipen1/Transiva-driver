<?php
error_reporting(0);
ini_set("display_errors", 0);

header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Headers: Content-Type, Authorization");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Content-Type: application/json; charset=UTF-8");

if($_SERVER["REQUEST_METHOD"] === "OPTIONS"){
    exit;
}

include "config.php";

function out($data){
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function normalize_room($value){
    $value = strtoupper(trim((string)$value));
    $value = str_replace("_", "-", $value);
    $value = preg_replace("/[^A-Z0-9\-]/", "", $value);

    if($value !== "" && strpos($value, "ROOM-") !== 0){
        $value = "ROOM-" . $value;
    }

    return $value;
}

function fix_file_url($path){
    $path = trim((string)$path);

    if($path === "" || strtolower($path) === "null"){
        return "";
    }

    $path = str_replace("\\", "/", $path);

    if(strpos($path, "http://") === 0 || strpos($path, "https://") === 0){
        return $path;
    }

    $base = "https://transiva.my.id/";

    if(substr($path, 0, 1) === "/"){
        return rtrim($base, "/") . $path;
    }

    return $base . ltrim($path, "/");
}


function chat_read_schema($conn){
    $q = mysqli_query($conn, "SHOW COLUMNS FROM chat_messages LIKE 'read_at'");
    if(!$q || mysqli_num_rows($q) === 0){
        @mysqli_query($conn, "ALTER TABLE chat_messages ADD COLUMN read_at DATETIME NULL DEFAULT NULL AFTER created_at");
    }
}

chat_read_schema($conn);

$room_id = normalize_room($_GET["room_id"] ?? "");
$last_id = intval($_GET["last_id"] ?? 0);
$viewer_type = strtolower(trim((string)($_GET["viewer_type"] ?? "")));
if(!in_array($viewer_type, ["customer","driver"], true)) $viewer_type = "";
$mark_read = intval($_GET["mark_read"] ?? 0) === 1;
$read_through_id = max(0, intval($_GET["read_through_id"] ?? 0));
$read_source = strtolower(trim((string)($_GET["read_source"] ?? "")));
$visible_ms = max(0, intval($_GET["visible_ms"] ?? 0));
$source = strtolower(trim((string)($_GET["source"] ?? "orders")));
if(!in_array($source, ["orders","pickup_orders"], true)) $source = "orders";

if($room_id === ""){
    out([
        "success" => false,
        "ended" => false,
        "status" => "",
        "message" => "room_id kosong",
        "driver" => null,
        "messages" => []
    ]);
}

$order_key = preg_replace("/^ROOM-/i", "", $room_id);

$status = "";
$driverUsername = "";
$dbRoomId = "";
$ended = false;

function find_order($conn, $source, $room_id, $order_key){
    if($source === "pickup_orders"){
        $sql = "SELECT id, order_id, status, driver_username AS driver, '' AS room_id FROM pickup_orders WHERE order_id=? OR CAST(id AS CHAR)=? ORDER BY id DESC LIMIT 1";
        $st = mysqli_prepare($conn, $sql);
        if(!$st) return null;
        mysqli_stmt_bind_param($st, "ss", $order_key, $order_key);
    } else {
        $sql = "SELECT id, order_id, status, driver, room_id FROM orders WHERE UPPER(REPLACE(room_id, '_', '-'))=? OR order_id=? OR CAST(id AS CHAR)=? ORDER BY id DESC LIMIT 1";
        $st = mysqli_prepare($conn, $sql);
        if(!$st) return null;
        mysqli_stmt_bind_param($st, "sss", $room_id, $order_key, $order_key);
    }
    mysqli_stmt_execute($st);
    $res = mysqli_stmt_get_result($st);
    $row = $res ? mysqli_fetch_assoc($res) : null;
    mysqli_stmt_close($st);
    return $row;
}

$order = find_order($conn, $source, $room_id, $order_key);
if(!$order){
    $fallback = $source === "pickup_orders" ? "orders" : "pickup_orders";
    $order = find_order($conn, $fallback, $room_id, $order_key);
    if($order) $source = $fallback;
}
if($order){
    $status = strtolower(trim((string)($order["status"] ?? "")));
    $driverUsername = trim((string)($order["driver"] ?? ""));
    $dbRoomId = normalize_room($order["room_id"] ?? "");
    if($dbRoomId !== "") $room_id = $dbRoomId;
    $dbOrderId = trim((string)($order["order_id"] ?? ""));
    if($dbOrderId !== "") $order_key = $dbOrderId;
    if($source === "pickup_orders") $room_id = normalize_room($order_key);
}

$driver = [
    "username" => $driverUsername,
    "name" => $driverUsername,
    "plate" => "",
    "driver_type" => "",
    "photo" => "",
    "driver_photo" => "",
    "vehicle_photo" => "",
    "latitude" => "",
    "longitude" => "",
    "is_online" => 0,
    "is_busy" => 0
];

if($driverUsername !== ""){
    $userSql = "
        SELECT
            username,
            role,
            driver_type,
            latitude,
            longitude,
            is_online,
            is_busy,
            plate,
            driver_photo,
            vehicle_photo
        FROM users
        WHERE username = ?
        LIMIT 1
    ";

    $userStmt = mysqli_prepare($conn, $userSql);

    if($userStmt){
        mysqli_stmt_bind_param($userStmt, "s", $driverUsername);
        mysqli_stmt_execute($userStmt);

        mysqli_stmt_bind_result(
            $userStmt,
            $uUsername,
            $uRole,
            $uDriverType,
            $uLatitude,
            $uLongitude,
            $uOnline,
            $uBusy,
            $uPlate,
            $uDriverPhoto,
            $uVehiclePhoto
        );

        if(mysqli_stmt_fetch($userStmt)){
            $driver = [
                "username" => trim((string)$uUsername),
                "name" => trim((string)$uUsername),
                "role" => trim((string)$uRole),
                "driver_type" => trim((string)$uDriverType),
                "latitude" => trim((string)$uLatitude),
                "longitude" => trim((string)$uLongitude),
                "lat" => trim((string)$uLatitude),
                "lng" => trim((string)$uLongitude),
                "is_online" => (int)$uOnline,
                "is_busy" => (int)$uBusy,
                "plate" => trim((string)$uPlate),
                "photo" => fix_file_url($uDriverPhoto),
                "driver_photo" => fix_file_url($uDriverPhoto),
                "vehicle_photo" => fix_file_url($uVehiclePhoto)
            ];
        }

        mysqli_stmt_close($userStmt);
    }
}

if($mark_read && $viewer_type !== "" && $read_through_id > 0) {
    /*
     * Read receipt hanya diterima dari ruang chat yang benar-benar foreground.
     * Notification poller / preview notifikasi tidak pernah mengirim token ini.
     */
    if($read_source !== "chat_room_foreground_v2" || $visible_ms < 1200){
        out([
            "success" => false,
            "read_marked" => 0,
            "message" => "Read receipt ditolak: chat belum terlihat aktif.",
            "read_through_id" => $read_through_id
        ]);
    }

    $opposite = $viewer_type === "customer" ? "driver" : "customer";
    $mark = mysqli_prepare($conn, "UPDATE chat_messages SET read_at=NOW() WHERE UPPER(REPLACE(room_id, '_', '-'))=? AND LOWER(sender_type)=? AND id<=? AND read_at IS NULL");
    $affected = 0;
    if($mark){
        mysqli_stmt_bind_param($mark, "ssi", $room_id, $opposite, $read_through_id);
        mysqli_stmt_execute($mark);
        $affected = mysqli_stmt_affected_rows($mark);
        mysqli_stmt_close($mark);
    }
    out([
        "success" => true,
        "read_marked" => max(0, (int)$affected),
        "read_through_id" => $read_through_id
    ]);
}

$endedList = [
    "finished",
    "completed",
    "finish",
    "canceled",
    "cancelled",
    "merchant_rejected"
];

if(in_array($status, $endedList, true)){
    $ended = true;
}

if($last_id > 0){
    $chatSql = "
        SELECT
            id,
            room_id,
            sender_type,
            message,
            created_at,
            read_at
        FROM chat_messages
        WHERE
            UPPER(REPLACE(room_id, '_', '-')) = ?
            AND id > ?
        ORDER BY id ASC
    ";

    $chatStmt = mysqli_prepare($conn, $chatSql);

    if(!$chatStmt){
        out([
            "success" => false,
            "ended" => $ended,
            "status" => $status,
            "message" => "Query chat gagal",
            "driver" => $driver,
            "messages" => []
        ]);
    }

    mysqli_stmt_bind_param($chatStmt, "si", $room_id, $last_id);

}else{
    $chatSql = "
        SELECT
            id,
            room_id,
            sender_type,
            message,
            created_at,
            read_at
        FROM chat_messages
        WHERE
            UPPER(REPLACE(room_id, '_', '-')) = ?
        ORDER BY id ASC
    ";

    $chatStmt = mysqli_prepare($conn, $chatSql);

    if(!$chatStmt){
        out([
            "success" => false,
            "ended" => $ended,
            "status" => $status,
            "message" => "Query chat gagal",
            "driver" => $driver,
            "messages" => []
        ]);
    }

    mysqli_stmt_bind_param($chatStmt, "s", $room_id);
}

if(!mysqli_stmt_execute($chatStmt)){
    out([
        "success" => false,
        "ended" => $ended,
        "status" => $status,
        "message" => "Gagal mengambil chat",
        "driver" => $driver,
        "messages" => []
    ]);
}

mysqli_stmt_bind_result(
    $chatStmt,
    $id,
    $msgRoomId,
    $senderType,
    $message,
    $createdAt,
    $readAt
);

$messages = [];

while(mysqli_stmt_fetch($chatStmt)){
    $messages[] = [
        "id" => (int)$id,
        "room_id" => $msgRoomId,
        "sender_type" => strtolower(trim((string)$senderType)),
        "message" => (string)$message,
        "created_at" => $createdAt,
        "read_at" => $readAt
    ];
}

mysqli_stmt_close($chatStmt);
mysqli_close($conn);

out([
    "success" => true,
    "ended" => $ended,
    "status" => $status,
    "room_id" => $room_id,
    "order_id" => $order_key,
    "driver" => $driver,
    "messages" => $messages
]);