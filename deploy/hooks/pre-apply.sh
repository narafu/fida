#!/usr/bin/env bash
# kista-infra reconcile.yml이 서버 적용 직전 app 디렉터리에서 인자 없이 실행하는 fida hook — 배포 금지 창 검사.
# 배포가 실행 중인 스케줄 스레드를 강제 종료해 매매 기록 처리가 유실될 수 있어, 스케줄러 구간(07:00 기동, 실패 시 07:20 재시도)에는 차단.
# 규칙은 .github/workflows/server-deploy.yml 배포 가드와 동일: KST 화~토 06:50~07:45 (양끝 포함). exit 1이면 적용 차단.
# 사용: (인자 없음)=현재 시각 판정 | --check "YYYY-MM-DD HH:MM"=해당 KST 시각 판정 | --self-test=경계 표 검증
set -euo pipefail

# 판정: $1=요일(%u, 1=월~7=일) $2=HHMM 숫자 — 차단이면 0, 허용이면 1
is_blocked() {
  [ "$1" -ge 2 ] && [ "$1" -le 6 ] && [ "$((10#$2))" -ge 650 ] && [ "$((10#$2))" -le 745 ]
}

# 날짜(YYYY-MM-DD)의 요일 — GNU date 우선, 실패 시 BSD date
dow_of() { date -d "$1" +%u 2>/dev/null || date -j -f %Y-%m-%d "$1" +%u 2>/dev/null; }

# 날짜를 date로 왕복 변환한 값 — GNU 우선, 실패 시 BSD
norm_date() { date -d "$1" +%F 2>/dev/null || date -j -f %Y-%m-%d "$1" +%F 2>/dev/null; }

# "YYYY-MM-DD HH:MM" 판정 — 형식 오류는 exit 2
check() {
  [[ "$1" =~ ^([0-9]{4}-[0-9]{2}-[0-9]{2})\ ([0-9]{2}):([0-9]{2})$ ]] || { echo "형식 오류: '$1' (YYYY-MM-DD HH:MM)" >&2; exit 2; }
  local d=${BASH_REMATCH[1]} hh=${BASH_REMATCH[2]} mm=${BASH_REMATCH[3]}
  # 시각 범위 검사
  [ "$((10#$hh))" -le 23 ] && [ "$((10#$mm))" -le 59 ] || { echo "시각 오류: '$1'" >&2; exit 2; }
  # 존재하지 않는 날짜(2026-02-31 등)는 왕복 변환이 달라져 거부
  local dow; dow=$(dow_of "$d") && [ "$(norm_date "$d")" = "$d" ] || { echo "날짜 오류: '$1'" >&2; exit 2; }
  is_blocked "$dow" "$hh$mm"
}

case "${1:-}" in
  --check)
    if check "${2:-}"; then echo "blocked: $2 KST"; exit 1; fi
    echo "allowed: $2 KST"; exit 0 ;;
  --self-test)
    rc=0
    # 기대값(blocked/allowed) 시각 — 2026-11-10=화, 12=목, 14=토, 15=일, 16=월
    for c in "blocked 2026-11-10 06:50" "blocked 2026-11-10 07:45" "blocked 2026-11-14 07:00" "blocked 2026-11-12 07:10" \
             "allowed 2026-11-10 06:49" "allowed 2026-11-10 07:46" "allowed 2026-11-16 07:10" "allowed 2026-11-15 07:10"; do
      want=${c%% *} at=${c#* }
      if check "$at"; then got=blocked; else got=allowed; fi
      [ "$got" = "$want" ] || { echo "FAIL: $at 기대 $want, 실제 $got"; rc=1; }
    done
    # 잘못된 날짜·시각은 서브셸에서 exit 2여야 한다
    for at in "2026-02-31 07:10" "2026-11-12 24:00" "2026-11-12 07:60"; do
      r=0; (check "$at") 2>/dev/null || r=$?
      [ "$r" -eq 2 ] || { echo "FAIL: $at 기대 exit 2, 실제 $r"; rc=1; }
    done
    [ "$rc" -eq 0 ] && echo "self-test OK"
    exit "$rc" ;;
esac

# live: 현재 KST 시각으로 판정
if is_blocked "$(TZ=Asia/Seoul date +%u)" "$(TZ=Asia/Seoul date +%H%M)"; then
  echo "::error::현재 KST $(TZ=Asia/Seoul date +%H:%M)는 fida 스케줄러 실행 시간대(화~토 06:50~07:45) — 배포가 실행 중인 스케줄 스레드를 강제 종료해 매매 기록 처리가 유실될 수 있어 차단합니다. 스케줄 종료 후 재실행하거나, 긴급 시 kista-infra Reconcile App force=true로 강제 적용하세요."
  exit 1
fi
echo "fida 스케줄 실행 시간대 아님 (KST $(TZ=Asia/Seoul date +%H:%M)) — 적용 진행"
