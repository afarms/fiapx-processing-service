# Plano de validação AWS do processamento

Estado: procedimento preparado; execução remota adiada até a infraestrutura e as aplicações do fluxo estarem rodando na cloud. Os testes locais não comprovam este plano. Na retomada, adaptar o harness ao ambiente implantado, registrar autorização para os recursos/dados abaixo e conferir o estado Terraform real. Não executar apply, enviar mensagens ou remover objetos como parte dos testes locais.

Preparação executável em [aws-harness.md](aws-harness.md): manifesto offline, Compose isolado com dois workers e imagem de teste com pontos antes do download/mídia/ACK e depois do PUT verificado. Os ganchos não substituem os cenários restantes nem evidência remota.

## Recursos e preparação

Região `us-east-1`; bucket privado `fiapx-media-files`; trabalho `fiapx-processing-work` e `fiapx-processing-work-dlq`; resultados `fiapx-videos-events` e `fiapx-videos-events-dlq`. URLs/ARNs efetivos devem vir dos outputs Terraform da conta conferida, nunca ser inferidos de um exemplo. Roles de aplicação: `fiapx-video-local` e `fiapx-processing-local`, via sessões temporárias separadas. Identidade real fornece duas contas de teste e seus tokens, mantidos fora de logs/Git.

O incremento Terraform de resultados/role foi provisionado e integrado. Conferir novamente os outputs e o estado efetivo antes do ensaio; o workflow de infraestrutura pode executar apply no PR. Não usar credencial de provisionamento nas aplicações.

Reservar janela exclusiva para estas filas: nenhuma aplicação ou carga alheia deve consumi-las. Conferir ausência de mensagens visíveis, invisíveis e atrasadas antes de começar. Se existir carga desconhecida, interromper e definir filas isoladas com permissões específicas; não purgar filas nem consumir dados alheios para abrir espaço.

Leituras de preparação (CLI com perfil apropriado; não exportar segredos em relatórios):

```bash
aws sts get-caller-identity --profile rafael-admin
aws sqs get-queue-attributes --queue-url "$PROCESSING_WORK_QUEUE_URL" --attribute-names All --profile rafael-admin
aws sqs get-queue-attributes --queue-url "$PROCESSING_RESULT_QUEUE_URL" --attribute-names All --profile rafael-admin
aws s3api get-public-access-block --bucket fiapx-media-files --profile rafael-admin
aws iam list-role-policies --role-name fiapx-processing-local --profile rafael-admin
aws iam list-role-policies --role-name fiapx-video-local --profile rafael-admin
```

Conferir também atributos das duas DLQs (URLs obtidas da infraestrutura), políticas inline exatas, trust e sessões efetivas. Trabalho/resultados Standard: retenção 345600 s, DLQ 1209600 s, visibilidade 120 s, maxReceiveCount 5 e TLS obrigatório. Worker: ACK somente em trabalho, envio somente em resultados, leitura originals, leitura/escrita/limpeza results. Vídeos: publicação de trabalho e consumo de resultados. Aplicações sem consumo de DLQ, IAM ou state Terraform.

## Execução controlada

Registrar um runId e manifesto local contendo os IDs de duas contas descartáveis, vídeos, eventIds, chaves originais/resultados e containers criados. Usar somente mídia sintética: dois vídeos válidos para concorrência, um arquivo inválido, um vídeo acima de 300 s e fixtures pequenas para falhas. Prefixos reais serão `originals/{ownerId}/{videoId}/{attemptId}` e `results/{ownerId}/{videoId}/{attemptId}/frames.zip`; não substituir os caminhos exigidos pelo contrato.

Executar vídeos e duas instâncias do worker localmente com a mesma base de jobs, volumes temporários separados e identificadores próprios. Preservar os containers de desenvolvimento. Limites iniciais: dois CPUs, 1 GiB e reserva de 3 GiB por worker; não representam dimensionamento EKS. Publicador/consumidores reais habilitados somente durante a janela; parametrizar suas URLs e perfis com arquivos privados. Reconciliação em vídeos requer `UPLOAD_ENABLED`, `UPLOAD_PUBLISHER_ENABLED` e `PROCESSING_RECONCILE_ENABLED` habilitados.

| Cenário | Ação e verificação exigida |
| --- | --- |
| Caminho completo | POST multipart autenticado; conferir QUEUED/outbox, consumo, ZIP S3 íntegro com PNGs 1 FPS, COMPLETED via GET e expiresAt=completedAt+24 h. Consultar com outro dono deve dar 404. |
| Concorrência real | Submeter dois vídeos capazes de manter processamento sobreposto. Registrar timestamps de início/fim de cada tentativa em instâncias distintas e demonstrar interseção dos intervalos. Se terminarem antes de sobrepor, aumentar a fixture dentro dos limites e repetir; dois sucessos sequenciais não atendem. |
| Duplicação/lease | Reenviar o envelope exato do mesmo vídeo enquanto ativo. Somente uma posse vence; parar o container proprietário, aguardar lease e comprovar retomada sem dois resultados. Não alterar token/versão à mão. |
| Mídia inválida/limites | Conferir FAILED sanitizado, ACK após transação e ausência de ZIP parcial referenciado. maxReceiveCount é independente das três tentativas de mídia. |
| Dependência indisponível | Interromper acesso de uma instância ao armazenamento/transporte de modo isolado e restaurar. Registrar ausência de perda e contagem de mídia sem gasto indevido. Não revogar policy compartilhada enquanto houver carga alheia. |
| Publicação/ACK incerto | Usar uma versão de harness de falhas controladas que interrompa entre commit e ACK, ou provoque falha de DeleteMessage. Registrar terminal/outbox antes do ACK, reentrega e ausência de nova mídia. Matar processo em momento não demonstrado não comprova a fronteira. |
| PUT/commit incerto | Registrar intenção, objeto íntegro, interrupção antes da confirmação e retomada/referência vencedora. Limpeza deve preservar objeto possivelmente referenciado, inclusive PUT tardio. Exige harness de falhas revisado antes de executar. |
| Perda de trabalho | Com consumidor parado e janela exclusiva, receber/remover somente a mensagem de um vídeo do manifesto. Manter outbox/aceite; no schema exclusivo de ensaio, antecipar published_at desse eventId para mais de seis horas. Comprovar republicação idêntica e conclusão. Não reduzir retenção global nem usar PurgeQueue. |
| Perda de resultado | Após terminal/outbox publicada, remover somente o resultado identificado antes do consumo por vídeos. A reconciliação deve reenviar trabalho e o worker deve republicar exatamente o terminal, sem renovar prazo nem executar FFmpeg. |
| DLQ/redrive | Inserir uma única mensagem malformada identificada pelo manifesto na janela exclusiva; comprovar DLQ sem criar FAILED artificial. Após diagnóstico, removê-la pelo receipt atual. Para evento válido identificável que falhou por configuração, corrigir a causa e redrive autorizado preservando eventId; não redrive de payload irrecuperável. |
| Conta inativa | Inativar conta de teste após aceite, inclusive antes do recebimento; worker conclui sem JWT. Novas consultas permanecem bloqueadas. Reativação não renova prazo do ZIP. |

Antes de executar as injeções de falha, concretizar comandos/harness e identificadores de containers/eventos no manifesto, revisar o escopo exato e obter autorização. O plano não substitui uma implementação testada desses ganchos. Nunca anunciar aprovação de um cenário apenas porque o comando de envio retornou sucesso.

Comandos operacionais de mensagem usam arquivos privados de envelope e receipt handle atual. Exemplos somente para os eventos do manifesto e na janela exclusiva:

```bash
aws sqs send-message --queue-url "$PROCESSING_WORK_QUEUE_URL" --message-body file://request.json --profile fiapx-video-local
aws sqs receive-message --queue-url "$TEST_QUEUE_URL" --max-number-of-messages 1 --wait-time-seconds 5 --profile rafael-admin
aws sqs delete-message --queue-url "$TEST_QUEUE_URL" --receipt-handle "$CURRENT_RECEIPT_HANDLE" --profile rafael-admin
```

Não executar receive/delete como perfil de aplicação em uma fila que não lhe pertence. Um resultado tardio já expirado pode ser representado em dataset isolado com timestamps coerentes no job e no envelope persistido; registrar essa injeção, sem alegar espera real de 24 h.

## Observabilidade e limpeza

Antes do ensaio de DLQ, provisionar/verificar alarmes para mensagens visíveis nas DLQs (>=1), erros recorrentes e reconciliação sem progresso, com destino operacional explicitamente autorizado. Logs locais e `reconciliation_count` não equivalem a alarmes cloud. Não enviar notificações a terceiros sem autorização do destino.

Depois de cada caso, comparar estado dos dois bancos, inbox/outbox, contadores de mídia e objetos do manifesto. Preservar evidências sanitizadas fora do Git. Ao concluir: parar apenas containers do ensaio, conferir ausência de PUT ativo/pendente, remover somente objetos originais/resultados enumerados no manifesto com credencial operacional autorizada e remover os schemas exclusivos. A role do worker não pode apagar originais. Se houver versionamento S3, incluir versionIds específicos; não usar exclusão recursiva do bucket. Não apagar dados de contas permanentes nem simular exclusão distribuída ainda não implementada.

Remover mensagens remanescentes somente quando identificadas e sem produtores ativos, conferir filas e DLQs e restaurar flags/configurações anteriores. Se a falha deixar posse ou objeto incerto, preservar para diagnóstico em vez de limpar. Registrar recursos remanescentes, limitações e resultado de cada cenário. A validação só termina quando resultados e limpeza autorizada forem conferidos.
