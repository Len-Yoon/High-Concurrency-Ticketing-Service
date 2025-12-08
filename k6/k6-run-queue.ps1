cd D:\JavaProjects\High_Concurrency_Ticketing_Service

$k6 = Get-ChildItem -Path "C:\Program Files","$env:LOCALAPPDATA\Programs" -Recurse -Filter "k6.exe" -ErrorAction SilentlyContinue | Select-Object -First 1

& $k6.FullName run .\k6\queue-load.js