import { useEffect, useMemo, useState } from "react";

type SeatLike = any;

type SeatChangedEvent = {
    type: "HELD" | "RELEASED" | "EXPIRED" | "CONFIRMED" | string;
    scheduleId: number;
    seatNo: string;
    reserved: boolean;
    userId?: number | null;
    occurredAt?: string;
};

function getSeatNo(s: SeatLike): string {
    return String(s.seatNo ?? s.seat_no ?? s.seatNumber ?? s.seat_number ?? "");
}

function getReserved(s: SeatLike): boolean {
    const v = s.reserved ?? s.isReserved ?? s.is_reserved ?? s.locked ?? s.held;
    return Boolean(v);
}

function setReserved(s: SeatLike, reserved: boolean): SeatLike {
    // 응답 모델이 어떤 형태든 reserved만 덮어쓰되 원본은 유지
    if ("reserved" in s) return { ...s, reserved };
    if ("isReserved" in s) return { ...s, isReserved: reserved };
    if ("is_reserved" in s) return { ...s, is_reserved: reserved };
    if ("locked" in s) return { ...s, locked: reserved };
    return { ...s, reserved };
}

export default function App() {
    const [scheduleId, setScheduleId] = useState<number>(1);
    const [userId, setUserId] = useState<number>(1);

    const [seats, setSeats] = useState<SeatLike[]>([]);
    const [loading, setLoading] = useState(false);
    const [err, setErr] = useState<string | null>(null);

    const seatCount = useMemo(() => seats.length, [seats]);

    async function refresh() {
        setLoading(true);
        setErr(null);
        try {
            // 너 서버 실제 경로에 맞게 이미 쓰고 있던 GET 그대로 유지해도 됨
            const res = await fetch(`/api/seats?scheduleId=${scheduleId}`);
            if (!res.ok) {
                const text = await res.text();
                throw new Error(`GET /api/seats failed: ${res.status} ${text}`);
            }
            const data = await res.json();

            // 응답이 { seats: [...] } 형태일 수도 있어서 방어
            const list = Array.isArray(data) ? data : Array.isArray(data?.seats) ? data.seats : [];
            setSeats(list);
        } catch (e: any) {
            setErr(e?.message ?? "unknown error");
        } finally {
            setLoading(false);
        }
    }

    function applySeatChange(evt: SeatChangedEvent) {
        const changedNo = String(evt.seatNo ?? "").trim().toUpperCase();
        if (!changedNo) return;

        setSeats((prev) =>
            prev.map((s) => {
                const no = getSeatNo(s).trim().toUpperCase();
                if (no !== changedNo) return s;
                return setReserved(s, Boolean(evt.reserved));
            })
        );
    }

    // ✅ 최초 로딩 + scheduleId 바뀔 때마다 새로고침
    useEffect(() => {
        refresh();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scheduleId]);

    // ✅ 폴링 제거, SSE 연결
    useEffect(() => {
        const url = `/api/seats/stream?scheduleId=${scheduleId}`;
        const es = new EventSource(url);

        let refreshTimer: number | null = null;

        es.addEventListener("seat", (ev: MessageEvent) => {
            try {
                const data: SeatChangedEvent = JSON.parse(ev.data);
                // scheduleId 방어
                if (Number(data.scheduleId) !== Number(scheduleId)) return;
                applySeatChange(data);
            } catch {
                // ignore
            }
        });

        es.addEventListener("ping", () => {});
        es.addEventListener("hello", () => {});

        es.onopen = () => {
            // 연결 복구되면 1회 refresh(선택)
            // refresh();
        };

        es.onerror = () => {
            // 끊김/재연결 과정에서 onerror 연속 호출될 수 있음 -> 1회만 refresh
            if (refreshTimer == null) {
                refreshTimer = window.setTimeout(() => {
                    refresh();
                    refreshTimer = null;
                }, 300);
            }
        };

        return () => {
            if (refreshTimer != null) window.clearTimeout(refreshTimer);
            es.close();
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scheduleId]);

    async function hold(seatNo: string) {
        const body = { scheduleId, seatNo, userId };
        const res = await fetch(`/api/tickets/hold`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        if (!res.ok) {
            const text = await res.text();
            alert(`hold 실패: ${res.status}\n${text}`);
        }
        // 성공/실패 상관없이 SSE가 오긴 하는데, 혹시 대비해서 refresh도 1번 가능
        // await refresh();
    }

    async function release(seatNo: string) {
        const body = { scheduleId, seatNo, userId };
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
    }

    return (
        <div style={{ padding: 16 }}>
            <h2>Ticketing UI</h2>

            <div style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 12 }}>
                <label>
                    scheduleId:&nbsp;
                    <input
                        type="number"
                        value={scheduleId}
                        onChange={(e) => setScheduleId(Number(e.target.value))}
                        style={{ width: 80 }}
                    />
                </label>
                <label>
                    userId:&nbsp;
                    <input
                        type="number"
                        value={userId}
                        onChange={(e) => setUserId(Number(e.target.value))}
                        style={{ width: 80 }}
                    />
                </label>

                <button onClick={refresh} disabled={loading}>
                    새로고침
                </button>

                <div>seats: {seatCount}</div>
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
                            key={seatNo}
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
