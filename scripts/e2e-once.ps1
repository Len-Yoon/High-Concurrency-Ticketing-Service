param(
  [long]$ScheduleId = 1,
  [long]$SeatId = 1,
  [string]$SeatNo = "A1",
  [long]$UserId = 1,
  [int]$Amount = 1000
)

& "$PSScriptRoot\e2e-hold-confirm.ps1" `
  -ScheduleId $ScheduleId `
  -SeatId $SeatId `
  -SeatNo $SeatNo `
  -UserId $UserId `
  -Amount $Amount
