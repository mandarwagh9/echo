#!/usr/bin/env bash
# Stand up echo-batch from nothing. Idempotent: re-running it is a redeploy.
#
# Everything here was run to produce the live deployment, rather than written
# from memory afterwards -- the IAM propagation retry below is in because the
# first attempt genuinely failed with "service account does not exist" a second
# after creating it.
set -euo pipefail

PROJECT="${ECHO_PROJECT:-agentbillboard}"
REGION="${ECHO_REGION:-us-central1}"
SPEECH_LOCATION="${ECHO_SPEECH_LOCATION:-us}"
INGEST="${ECHO_INGEST_BUCKET:-agentbillboard-echo-ingest}"
RESULTS="${ECHO_RESULTS_BUCKET:-agentbillboard-echo-results}"
DB="${ECHO_FIRESTORE_DB:-echo}"
SA="echo-batch@${PROJECT}.iam.gserviceaccount.com"

echo "== APIs =="
gcloud services enable \
  speech.googleapis.com storage.googleapis.com firestore.googleapis.com \
  run.googleapis.com cloudscheduler.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com --project "$PROJECT"

echo "== buckets =="
# A one-day delete rule on both, so audio cannot outlive its purpose even if the
# reaper never runs. The reaper deleting on commit is the mechanism; this is the
# backstop, and it belongs in infrastructure rather than application logic.
for bucket in "$INGEST" "$RESULTS"; do
  gcloud storage buckets create "gs://$bucket" --project "$PROJECT" \
    --location "$SPEECH_LOCATION" --uniform-bucket-level-access 2>/dev/null || true
  gcloud storage buckets update "gs://$bucket" --project "$PROJECT" \
    --lifecycle-file="$(dirname "$0")/lifecycle.json"
done

echo "== firestore =="
gcloud firestore databases create --database="$DB" --location=nam5 \
  --type=firestore-native --project "$PROJECT" 2>/dev/null || true

echo "== service account =="
gcloud iam service-accounts create echo-batch --project "$PROJECT" \
  --display-name "Echo batch transcription job" 2>/dev/null || true

# IAM is eventually consistent; a binding issued immediately after creation can
# be rejected with "does not exist".
for role in roles/speech.client roles/datastore.user; do
  until gcloud projects add-iam-policy-binding "$PROJECT" \
    --member="serviceAccount:$SA" --role="$role" --condition=None \
    --format="value(etag)" >/dev/null 2>&1; do sleep 5; done
done

# Storage is granted per bucket, not project-wide: this job has no business
# reading the other four buckets in this project.
for bucket in "$INGEST" "$RESULTS"; do
  gcloud storage buckets add-iam-policy-binding "gs://$bucket" \
    --member="serviceAccount:$SA" --role=roles/storage.objectAdmin \
    --project "$PROJECT" >/dev/null
done

echo "== job =="
gcloud run jobs deploy echo-batch \
  --source "$(dirname "$0")" \
  --region "$REGION" --project "$PROJECT" \
  --service-account "$SA" \
  --set-env-vars "ECHO_PROJECT=$PROJECT,ECHO_SPEECH_LOCATION=$SPEECH_LOCATION,ECHO_INGEST_BUCKET=$INGEST,ECHO_RESULTS_BUCKET=$RESULTS,ECHO_FIRESTORE_DB=$DB" \
  --max-retries 1 --task-timeout 900s --memory 512Mi

# run.developer, not run.invoker. The nightly summariser passes container args
# via the v2 :run endpoint, and overrides need run.jobs.runWithOverrides, which
# invoker does not carry -- Scheduler got a bare 403 until this was granted.
# Scoped to the job rather than the project.
gcloud run jobs add-iam-policy-binding echo-batch --region "$REGION" \
  --project "$PROJECT" --member="serviceAccount:$SA" \
  --role=roles/run.developer >/dev/null

echo "== schedule =="
# Off the :00/:15/:30/:45 marks on purpose -- every cron on the planet lands
# there. Four times an hour is about submission latency, not transcription:
# audio uploaded just after a tick waits at most 15 minutes to be submitted.
gcloud scheduler jobs create http echo-batch-tick \
  --project "$PROJECT" --location "$REGION" \
  --schedule "7,22,37,52 * * * *" --time-zone "Asia/Kolkata" \
  --uri "https://$REGION-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/$PROJECT/jobs/echo-batch:run" \
  --http-method POST --oauth-service-account-email "$SA" \
  --description "Reap finished Chirp 3 batches, submit newly uploaded audio" \
  2>/dev/null || gcloud scheduler jobs update http echo-batch-tick \
  --project "$PROJECT" --location "$REGION" --schedule "7,22,37,52 * * * *"

# The v2 endpoint, not v1. `apis/run.googleapis.com/v1/namespaces/...:run`
# silently ignores overrides -- the execution starts with the image's default
# args and then sits retrying, which looks like a stuck job rather than a
# rejected request.
gcloud scheduler jobs create http echo-summarise-nightly \
  --project "$PROJECT" --location "$REGION" \
  --schedule "3 23 * * *" --time-zone "Asia/Kolkata" \
  --uri "https://$REGION-run.googleapis.com/v2/projects/$PROJECT/locations/$REGION/jobs/echo-batch:run" \
  --http-method POST --oauth-service-account-email "$SA" \
  --message-body '{"overrides":{"containerOverrides":[{"args":["--summarise"]}]}}' \
  --headers "Content-Type=application/json" \
  --description "23:03 IST: write up the day from its transcribed segments" \
  2>/dev/null || true

echo
echo "Deployed. Upload audio to gs://$INGEST/pending/ and it is transcribed"
echo "within ~15 minutes plus batch turnaround."
echo "Run a pass now:  gcloud run jobs execute echo-batch --region $REGION"
