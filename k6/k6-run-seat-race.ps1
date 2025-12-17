cd D:\JavaProjects\High_Concurrency_Ticketing_Service
$env:BASE_URL="http://localhost:8080"
$env:SCHEDULE_ID="1"
$env:SEAT_START="1"
$env:SEAT_END="50"
k6 run .\k6\seat-hold-race.js