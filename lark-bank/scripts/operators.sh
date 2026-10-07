#!/usr/bin/env bash
# What the bank's manifests need installed first, in whichever cluster kubectl points at: CloudNativePG, which runs
# the journal's databases (bank spec 0008), and the Barman Cloud plugin that backs them up (spec 0009), with the
# cert-manager it needs. All pinned; each must answer before anything naming it is applied, or the apply is refused.
set -euo pipefail

cnpg="${CNPG_VERSION:-1.30.1}"
kubectl apply --server-side -f \
  "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-${cnpg%.*}/releases/cnpg-${cnpg}.yaml"
kubectl -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=5m

certmanager="${CERT_MANAGER_VERSION:-v1.18.2}"
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${certmanager}/cert-manager.yaml"
kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=5m

# The plugin runs beside the operator, in cnpg-system.
barman="${BARMAN_PLUGIN_VERSION:-v0.15.0}"
kubectl apply -f "https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/${barman}/manifest.yaml"
kubectl -n cnpg-system rollout status deployment/barman-cloud --timeout=5m
