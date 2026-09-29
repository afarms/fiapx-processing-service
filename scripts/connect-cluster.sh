#!/bin/bash
set -euo pipefail
if [[ -z "${ADMIN_INSTANCE_ID:-}" ]]; then
  echo '::error::ADMIN_INSTANCE_ID não configurada. No repositório GitHub, acesse Settings > Secrets and variables > Actions > Variables e informe o ID da EC2 de administração (i-...). Consulte o output application_delivery do Terraform.'
  exit 1
fi
if [[ ! "$ADMIN_INSTANCE_ID" =~ ^i-([0-9a-f]{8}|[0-9a-f]{17})$ ]]; then
  echo '::error::ADMIN_INSTANCE_ID inválida. Informe somente o ID da EC2 de administração (i-...), sem espaços, nome ou ARN, em Settings > Secrets and variables > Actions > Variables.'
  exit 1
fi
endpoint=$(aws eks describe-cluster --name fiapx --query cluster.endpoint --output text)
cluster=$(aws eks describe-cluster --name fiapx --query cluster.arn --output text)
export KUBECONFIG="$RUNNER_TEMP/fiapx-kubeconfig"
aws eks update-kubeconfig --name fiapx --alias fiapx --kubeconfig "$KUBECONFIG" >/dev/null
kubectl config set-cluster "$cluster" --server=https://127.0.0.1:18443 --tls-server-name="${endpoint#https://}" >/dev/null
printf 'KUBECONFIG=%s\n' "$KUBECONFIG" >> "$GITHUB_ENV"
# Only a network tunnel. No deployment command runs on the EC2 host.
aws ssm start-session --target "$ADMIN_INSTANCE_ID" --document-name fiapx-eks-tunnel > "$RUNNER_TEMP/fiapx-tunnel.log" 2>&1 &
tunnel_pid=$!
printf 'TUNNEL_PID=%s\n' "$tunnel_pid" >> "$GITHUB_ENV"
for ((attempt=0; attempt<30; attempt++)); do
  kill -0 "$tunnel_pid" 2>/dev/null || { echo '::error::Não foi possível abrir o túnel SSM. Confira se ADMIN_INSTANCE_ID aponta para a EC2 de administração atual, ligada e online no Systems Manager, na região configurada. Se a instância foi substituída, atualize a variável no GitHub. Verifique também as permissões IAM da role de deploy.'; exit 1; }
  if kubectl get services -n fiapx --request-timeout=5s >/dev/null 2>&1; then exit 0; fi
  sleep 2
done
echo 'Private EKS connection failed; inspect tunnel and EKS access entry.'
exit 1
