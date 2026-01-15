import { useEffect, useMemo, useRef, useState } from "react";

type SeatLike = any;

type SeatChangedEvent = {
    type: "HELD" | "RELEASED" | "EXPIRED" | "CONFIRMED" | string;
    scheduleId: number;
    seatNo: string;
    reserved: boolean;
    userId?: number | null;
    occurredAt?: string;
};

type SseStatus = "CONNECTING" | "OPEN" | "ERROR" | "CLOSED";

function getSeatNo(s: SeatLike): string {
    return String(s.seatNo ?? s.seat_no ?? s.seatNumber ?? s.seat_number ?? "");
}

function getReserved(s: SeatLike): boolean {
    const v = s.reserved ?? s.isReserved ?? s.is_reserved ?? s.locked ?? s.held;
    return Boolean(v);
}

function setReserved(s: SeatLike, reserved: boolean): SeatLike {
    // 응답 모델이 어떤 형태든 reserved만 덮어쓰되 원본은 유지
    if (s && typeof s === "object") {
        if ("reserved" in s) return { ...s, reserved };
        if ("isReserved" in s) return { ...s, isReserved: reserved };
        if ("is_reserved" in s) return { ...s, is_reserved: reserved };
        if ("locked" in s) return { ...s, locked: reserved };
    }
    return { ...s, reserved };
}

function normalizeSeatNo(seatNo: string): string {
    return String(seatNo ?? "").trim().toUpperCase();
}

function normalizeReserved(evt: SeatChangedEvent): boolean {
    // 이벤트 타입이 더 신뢰할만함(이중 방어)
    if (evt.type === "RELEASED" || evt.type === "EXPIRED") return false;
    if (evt.type === "HELD" || evt.type === "CONFIRMED") return true;
    return Boolean(evt.reserved);
}

export default function App() {
    const [scheduleId, setScheduleId] = useState<number>(1);
    const [userId, setUserId] = useState<number>(1);

    const [seats, setSeats] = useState<SeatLike[]>([]);
    const [loading, setLoading] = useState(false);
    const [err, setErr] = useState<string | null>(null);

    const [sseStatus, setSseStatus] = useState<SseStatus>("CONNECTING");

    const seatCount = useMemo(() => seats.length, [seats]);

    // refresh 중복 호출 방지(연타/동시 호출)
    const refreshInFlight = useRef<Promise<void> | null>(null);

    async function refresh() {
        if (refreshInFlight.current) return refreshInFlight.current;

        const p = (async () => {
            setLoading(true);
            setErr(null);
            try {
                const res = await fetch(`/api/seats?scheduleId=${scheduleId}`);
                if (!res.ok) {
                    const text = await res.text();
                    throw new Error(`GET /api/seats failed: ${res.status} ${text}`);
                }
                const data = await res.json();

                // 응답이 { seats: [...] } 형태일 수도 있어서 방어
                const list = Array.isArray(data)
                    ? data
                    : Array.isArray(data?.seats)
                        ? data.seats
                        : [];

                setSeats(list);
            } catch (e: any) {
                setErr(e?.message ?? "unknown error");
            } finally {
                setLoading(false);
                refreshInFlight.current = null;
            }
        })();

        refreshInFlight.current = p;
        return p;
    }

    function applySeatChange(evt: SeatChangedEvent) {
        const changedNo = normalizeSeatNo(evt.seatNo ?? "");
        if (!changedNo) return;

        const reserved = normalizeReserved(evt);

        setSeats((prev) =>
            prev.map((s) => {
                const no = normalizeSeatNo(getSeatNo(s));
                if (no !== changedNo) return s;
                return setReserved(s, reserved);
            })
        );
    }

    // ✅ 최초 로딩 + scheduleId 바뀔 때마다 새로고침
    useEffect(() => {
        refresh();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scheduleId]);

    // ✅ SSE 연결 (scheduleId 변경 시 재연결)
    useEffect(() => {
        const url = `/api/seats/stream?scheduleId=${scheduleId}`;

        setSseStatus("CONNECTING");
        const es = new EventSource(url);

        let refreshTimer: number | null = null;

        // 안전 폴링(정합성 보정용): 너무 자주 말고 15초 권장
        const safetyInterval = window.setInterval(() => {
            refresh();
        }, 15000);

        es.addEventListener("seat", (ev: MessageEvent) => {
            try {
                const data: SeatChangedEvent = JSON.parse(ev.data);
                if (Number(data.scheduleId) !== Number(scheduleId)) return;
                applySeatChange(data);
            } catch {
                // ignore
            }
        });

        es.addEventListener("ping", () => {});
        es.addEventListener("hello", () => {});

        es.onopen = () => {
            setSseStatus("OPEN");
            // 연결 복구 직후 1회 refresh 하고 싶으면 주석 해제
            // refresh();
        };

        es.onerror = () => {
            setSseStatus("ERROR");
            // 끊김/재연결 과정에서 onerror 연속 호출될 수 있음 -> 1회만 refresh 예약
            if (refreshTimer == null) {
                refreshTimer = window.setTimeout(() => {
                    refresh();
                    refreshTimer = null;
                }, 300);
            }
        };

        return () => {
            setSseStatus("CLOSED");
            if (refreshTimer != null) window.clearTimeout(refreshTimer);
            window.clearInterval(safetyInterval);
            es.close();
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scheduleId]);

    async function hold(seatNo: string) {
        const body = { scheduleId, seatNo, userId };

        try {
            const res = await fetch(`/api/tickets/hold`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (!res.ok) {
                const text = await res.text();
                alert(`hold 실패: ${res.status}\n${text}`);
            }
            // SSE가 오겠지만, 상태 꼬임 대비해서 원하면 1회 refresh:
            // await refresh();
        } catch (e: any) {
            alert(`hold 네트워크 오류: ${e?.message ?? e}`);
        }
    }

    async function release(seatNo: string) {
        const body = { scheduleId, seatNo, userId };

        try {
            const res = await fetch(`/api/tickets/release`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (!res.ok) {
                const text = await res.text();
                alert(`release 실패: ${res.status}\n${text}`);
            }
            // await refresh();
        } catch (e: any) {
            alert(`release 네트워크 오류: ${e?.message ?? e}`);
        }
    }

    return (
        <div style={{ padding: 16 }}>
            <h2>Ticketing UI</h2>

            <div style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 12, flexWrap: "wrap" }}>
                <label>
                    scheduleId:&nbsp;
                    <input
                        type="number"
                        value={scheduleId}
                        onChange={(e) => setScheduleId(Number(e.target.value))}
                        style={{ width: 90 }}
                    />
                </label>

                <label>
                    userId:&nbsp;
                    <input
                        type="number"
                        value={userId}
                        onChange={(e) => setUserId(Number(e.target.value))}
                        style={{ width: 90 }}
                    />
                </label>

                <button onClick={refresh} disabled={loading}>
                    새로고침
                </button>

                <div>seats: {seatCount}</div>
                <div>SSE: {sseStatus}</div>

                {loading && <div>loading...</div>}
            </div>

            {err && <div style={{ marginBottom: 12, color: "crimson" }}>{err}</div>}

            <div
                style={{
                    display: "grid",
                    gridTemplateColumns: "repeat(10, 1fr)",
                    gap: 8,
                }}
            >
                {seats.map((s, idx) => {
                    const seatNo = getSeatNo(s) || `#${idx}`;
                    const reserved = getReserved(s);

                    return (
                        <button
                            key={`${seatNo}-${idx}`}
                            onClick={() => (reserved ? release(seatNo) : hold(seatNo))}
                            style={{
                                padding: "10px 6px",
                                borderRadius: 8,
                                border: "1px solid #ddd",
                                background: reserved ? "#ff4d4f" : "#f5f5f5",
                                color: reserved ? "white" : "black",
                                cursor: "pointer",
                            }}
                            title={reserved ? "release" : "hold"}
                        >
                            {seatNo}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
