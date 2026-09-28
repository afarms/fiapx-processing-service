# Persistência dos trabalhos de processamento

O gateway de jobs mantém uma transação curta por operação, com row lock PostgreSQL e relógio do banco. O core não depende de Spring/JPA; entidade, mapper e Spring Data ficam na infraestrutura e são compostos pelo BeanConfig.

## Identidade e posse

Um job por videoId. Proprietário, referência imutável do original, tamanho/hash e nome não podem ser substituídos por mensagem conflitante. A inbox é única por eventId e conserva o conteúdo recebido. Um novo eventId para o mesmo trabalho não cria outro job; eventId reutilizado com conteúdo divergente falha e reverte a inserção.

`acquire` retorna ACQUIRED, BUSY ou TERMINAL. Somente uma posse ativa é admitida. Cada aquisição incrementa attempt e produz token UUID novo, distinto do contador mediaAttempts. Heartbeat e mutações exigem o token vigente e lease ainda não vencido. Retentativas por dependência não incrementam mediaAttempts; `beginMedia` registra a intenção de execução antes do subprocesso e permite no máximo três execuções. A política de classificação das falhas e agendamento pelo consumidor ainda será integrada.

## Tabelas

| Tabela | Responsabilidade |
| --- | --- |
| processing_jobs | Estado atual, posse, contador de mídia, versão e referência/timestamps do resultado |
| processing_inbox | Deduplicação persistida e conclusão do efeito por evento |
| processing_attempts | Histórico de aquisições, lease, início de mídia e encerramento |
| processing_result_intents | Intenção imutável de armazenamento por tentativa para recuperação e limpeza futura |
| processing_outbox | Envelope de início/resultado, eventId estável, versão única e campos para publicação recuperável |

Não existe TTL automático de 24 horas nesses registros. Esse prazo é de disponibilidade do ZIP, não de idempotência ou retenção da outbox. SQL de inserção de job usa ON CONFLICT DO NOTHING; nunca sobrescreve/upserta o estado de trabalho existente. Hibernate salva somente a entidade existente e bloqueada. Novas migrations devem ser adicionadas ao changelog, sem alterar changesets já aplicados.

## Conclusão e ACK futuro

`complete` pressupõe que o adapter de armazenamento confirmou o objeto S3 registrado por `prepareResult`. Não realiza I/O remoto dentro da transação. Resultado terminal, outbox e conclusão da inbox são atômicos; falha em qualquer escrita reverte todos. A transação usa REQUIRES_NEW: o método só devolve sucesso após seu próprio commit, mesmo quando o chamador já tem transação aberta. Chamadores não devem adquirir locks próprios dos mesmos jobs antes de invocar o gateway.

`completedAt` é o timestamp do banco registrado na transação terminal confirmada; `expiresAt` é fixado junto, +24 horas. Replay e reinício não renovam esse prazo. Job FAILED também é terminal durável, com evento de falha e sem expiresAt.

Estado terminal só retorna recibo TERMINAL se sua outbox terminal existir na versão correspondente. Erro/commit incerto propaga exceção; o consumidor futuro não pode dar ACK nesse caminho. SQS/DeleteMessage ainda não está implementado neste incremento. A conclusão SQL não comprova gravação S3 ou processamento real de mídia.

Intenção de resultado permanece após perda de posse. Nova tentativa pode confirmar o mesmo objeto íntegro, sem gastar tentativa de mídia. Se adapter verificar ausência tanto em S3 quanto localmente, `discardMissingResult` libera posse e exige nova aquisição/token antes de reexecutar; isso impede sobrescrever a chave da tentativa antiga.

## Testes reproduzíveis

`make verify` executa unitários/cobertura e isolamento do core. `make integration` carrega a conexão do `.env`, exige localhost/127.0.0.1, cria schema aleatório exclusivo, aplica Liquibase e executa cenários reais antes de remover somente o schema do ensaio. Não cria container, usa o PostgreSQL local já disponível e não acessa AWS.

Integrações cobrem migration/restart, duas aquisições concorrentes, lease vencido, resultado antigo, duplicatas/conflitos, rollback de terminal por falha na outbox, limite de tentativas, intenção preservada, disputa de conclusão e commit independente da transação do chamador. Não são evidência de concorrência de extração FFmpeg ou de ACK SQS real.
