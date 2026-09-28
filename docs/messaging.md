# Consumo e publicação SQS

O worker usa AWS SDK Java v2 para receber uma mensagem de trabalho por vez, com long polling de 20 segundos. Uma reserva local impede recebimento enquanto há trabalho ativo; falta de espaço para a reserva de disco também suspende recebimentos. O envelope `VideoProcessingRequested` v1 é validado antes de acessar o banco ou o original: UUIDs canônicos, tipo/versão, bucket configurado, referência de original, tamanho e SHA-256. Não há consulta de identidade nem dependência de JWT para executar trabalho já aceito.

## Confirmação e retentativas

O recebimento não exclui a mensagem. `DeleteMessage` usa o receipt handle daquela entrega e só é chamado após retorno terminal confirmado pelo gateway transacional ou verificação de duplicata terminal com outbox existente. Sucesso e falha determinística de mídia recebem ACK após commit. Falha de banco, S3, commit incerto, mensagem inválida/conflitante ou duplicata ativa não recebem ACK. Mensagens inválidas seguem a política de redrive da fila para investigação; não geram uma falha de negócio artificial.

Lease SQL e visibilidade são renovados na aquisição e a cada 30 segundos, por executor independente, com duração inicial de 120 segundos. O relógio do banco governa a posse. Um prazo monotônico conservador limita a execução local; erro de renovação SQL/SQS revoga imediatamente essa autorização. Subprocessos verificam a autorização durante execução, e confirmações SQL exigem token vigente. Encerramento do consumidor revoga a autorização atual. Renovação e ACK nunca são atômicos com o banco.

Antes de iniciar FFmpeg, o worker registra `beginMedia`. Falhas transitórias de mídia/timeout aguardam 30 e 60 segundos, com no máximo três execuções. Falha de dependência antes da mídia não gasta tentativa. Resultado pendente é recuperado antes de avaliar o orçamento de mídia: um ZIP já produzido pode ser confirmado mesmo após a terceira execução. Falhas operacionais preservam a intenção de resultado e usam espera limitada; a próxima entrega consulta novamente o estado durável.

Se o commit terminal ocorreu e a exclusão SQS falhou, a entrega seguinte verifica o terminal sem repetir mídia. O mesmo vale quando houve falha de limpeza local após o commit. Não há ACK em `finally`. Entregas não terminais válidas têm visibilidade adiada por 30 segundos; o prazo SQL de retomada continua impedindo início antecipado.

## Outbox e limpeza

O publisher é independente do consumidor. Cada claim SQL curto usa `FOR UPDATE SKIP LOCKED`, token e prazo de 120 segundos. O envio SQS acontece fora da transação. A marca de publicado exige o token ainda vigente; publicador antigo não altera a posse nova. Falha de envio ou commit mantém o payload e `eventId` para reenvio, com espera de 30 segundos. Cada rodada publica no máximo dez eventos; cada envio tem seu próprio claim.

O scheduler tem três threads para consumo, publicação e limpeza; heartbeat tem executor próprio. A limpeza roda a cada 60 segundos, verifica até vinte intenções órfãs por rodada e respeita as proteções de posse/referência do armazenamento. Não expira resultados válidos. Reconciliação de trabalhos/resultados ausentes das filas ainda não está implementada.

## Configuração

Por padrão `PROCESSING_MESSAGING_ENABLED=false` e nenhuma fila é acessada. Para habilitar, configurar também `PROCESSING_STORAGE_ENABLED=true`, `PROCESSING_MEDIA_BUCKET`, `PROCESSING_WORK_QUEUE_URL`, `PROCESSING_RESULT_QUEUE_URL` e `AWS_REGION`. Filas devem ser Standard com URL HTTPS da AWS. Credenciais seguem a cadeia padrão do SDK ou o perfil local `PROCESSING_AWS_PROFILE`; o Compose não monta credenciais do host automaticamente. Permissões do worker devem permitir receber/renovar/excluir apenas na fila de trabalho e enviar na fila de resultados, além do armazenamento restrito.

`make verify` cobre contrato, ACK negativo, perdas de posse, limites de recebimento e falhas de publicação com SQS simulado. `make integration` usa PostgreSQL local real para transação terminal revertida sem ACK, falha de ACK após commit, redelivery terminal e claims concorrentes da outbox. Esses testes não acessam S3/SQS reais. Validação cloud, dois workers integrados e consumo de resultados pelo serviço de vídeos continuam pendentes.

Referências do transporte: [DeleteMessage](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_DeleteMessage.html) e [ChangeMessageVisibility](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ChangeMessageVisibility.html).
