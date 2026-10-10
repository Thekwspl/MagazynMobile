#!/usr/bin/env bash
set -euo pipefail

api="${1:?Podaj API emulatora}"
case "$api" in 26|35) ;; *) exit 2 ;; esac
stage="startup"
report_root="instrumentation-api-$api"

capture() {
    local destination="$report_root/$stage"
    mkdir -p "$destination"
    adb logcat -d > "$destination/logcat.txt"
    if [ -d app/build/reports/androidTests/connected ]; then
        mkdir -p "$destination/reports"
        cp -a app/build/reports/androidTests/connected/. "$destination/reports/"
    fi
    if [ -d app/build/outputs/androidTest-results/connected ]; then
        mkdir -p "$destination/results"
        cp -a app/build/outputs/androidTest-results/connected/. "$destination/results/"
    fi
}
trap capture EXIT

run_tests() {
    stage="$1"
    shift
    echo "API $api: $stage"
    gradle :app:connectedDebugAndroidTest --rerun --no-daemon --max-workers=1 "$@"
    capture
}

if [ "$api" = 26 ]; then
    for attempt in 1 2 3; do
        run_tests "selected-$attempt" '-Pandroid.testInstrumentationRunnerArguments.class=pl.magazyn.mobile.data.PartialOrderFulfillmentTest#selectedShipyardRecipientUsesExistingShipyardIssuePath'
    done
    run_tests partial-order-class '-Pandroid.testInstrumentationRunnerArguments.class=pl.magazyn.mobile.data.PartialOrderFulfillmentTest'
fi
run_tests full-suite
