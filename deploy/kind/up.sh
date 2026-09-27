#!/usr/bin/env bash
# Builds the image, creates a local kind cluster, deploys the dependencies and the chart, and runs the smoke test.
# Requires docker, kind, kubectl and helm on the PATH.
set -euo pipefail

CLUSTER=${CLUSTER:-paywallet}
NAMESPACE=${NAMESPACE:-paywallet}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" > /dev/null

docker build -t paywallet:dev "$ROOT"
# Tagged by content: with a fixed tag, a rebuilt image would not change the pod template and never roll out.
TAG=dev-$(docker image inspect paywallet:dev --format '{{.Id}}' | cut -d: -f2 | cut -c1-12)
docker tag paywallet:dev "paywallet:$TAG"
kind load docker-image "paywallet:$TAG" --name "$CLUSTER"

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n "$NAMESPACE" -f "$ROOT/deploy/kind/dependencies.yaml"
kubectl wait -n "$NAMESPACE" --for=condition=Available deployment --all --timeout=300s

# Every replica must sign and verify tokens with the same key, and it must survive upgrades.
JWT_KEY=$(mktemp)
trap 'rm -f "$JWT_KEY"' EXIT
kubectl get secret paywallet -n "$NAMESPACE" -o jsonpath='{.data.JWT_PRIVATE_KEY}' 2>/dev/null \
  | base64 -d > "$JWT_KEY" || true
if [ ! -s "$JWT_KEY" ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$JWT_KEY" 2>/dev/null
fi

helm upgrade --install paywallet "$ROOT/deploy/helm/paywallet" -n "$NAMESPACE" \
  -f "$ROOT/deploy/kind/values-kind.yaml" --set image.tag="$TAG" \
  --set-file secrets.JWT_PRIVATE_KEY="$JWT_KEY" --wait --timeout 10m
helm test paywallet -n "$NAMESPACE" --logs

echo
echo "API:    kubectl -n $NAMESPACE port-forward svc/paywallet 8080:8080"
echo "Health: kubectl -n $NAMESPACE port-forward svc/paywallet 8081:8081"
echo "Remove: kind delete cluster --name $CLUSTER"
