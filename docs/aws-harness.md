# Harness de ensaio AWS

Este incremento prepara a execução; não constitui evidência de aprovação em AWS. Use junto do [plano de validação](aws-validation.md). O controlador Python funciona offline, não executa AWS CLI e não apaga objetos ou mensagens. Os pontos de falha pertencem a `src/test`, em pacote fora do component scan da aplicação. Somente o target Docker `aws-harness` contém o entrypoint especial; o JAR e o target padrão `runtime` não contêm o harness.

A execução real foi adiada até a infraestrutura e as aplicações estarem na cloud. O Compose abaixo preserva a preparação local para reaproveitamento; não é uma implantação Kubernetes nem uma instrução para antecipar o ensaio. Na retomada, adaptar isolamento, volumes, credenciais de workload e controles ao ambiente cloud e revisar o lote antes da execução.

## Preparar e revisar

Requisitos: Python 3.10+, Java 21, Docker Compose, checkout irmão de vídeos com imagem local construída. Todos os dados gerados ficam em `.local/aws-harness`, excluídos do Git e do contexto Docker. O build e os testes locais não precisam de credenciais AWS:

```bash
make verify
make integration
make harness-check
make harness-image
```

Salve `terraform output -json` do backend de infraestrutura em arquivo privado, usando o perfil de leitura da conta conferida. O controlador rejeita divergências de conta, região, nomes de filas, bucket e roles:

```bash
python scripts/aws_harness.py prepare --outputs /caminho/privado/outputs.json --account CONTA_CONFERIDA
export HARNESS_RUN_DIRECTORY='D:/caminho/fiapx-processing-service/.local/aws-harness/fiapx-aws-ID_GERADO'
make harness-config
make harness-fixtures
```

No PowerShell, use `$env:HARNESS_RUN_DIRECTORY='D:/...'`. O runId também é o nome do projeto Compose. O manifesto é criado uma vez; uma nova preparação gera outro runId. A fixture `concurrent.mp4` contém 30 s, 1280×720, 10 fps e pode alimentar dois uploads distintos; `too-long.mp4` contém 301 s em resolução pequena; `invalid.mp4` é texto. Não presumir concorrência só pela duração da fixture: conferir os intervalos reais e, se necessário, preparar uma fixture maior dentro dos limites.

Antes de iniciar o perfil `execution`:

1. Conferir atributos e ausência de mensagens nas quatro filas, reservar janela exclusiva e interromper se houver carga desconhecida.
2. Registrar duas contas descartáveis reais de identidade com `python scripts/aws_harness.py owner --run "$HARNESS_RUN_DIRECTORY" --id UUID`. Não registrar senhas/tokens no manifesto.
3. Preencher `worker.env` com sessão temporária da role do worker e `video.env` com sessão da role de vídeos. As três variáveis AWS devem pertencer à mesma sessão em cada arquivo. Manter identidade e sua chave de serviço reais; copiar somente a chave **pública** de verificação para `identity-public.pem`. Não montar o diretório pessoal `.aws` nem usar sessão administrativa nas aplicações.
4. Conferir a validade das sessões antes do lote. Se expirarem, parar consumidores, renovar arquivos e recriar somente os serviços do ensaio. Os arquivos são carregados na criação do container.
5. Revisar dados/ações do lote e obter autorização remota. Só então mudar `PROCESSING_AWS_HARNESS_APPROVED=true` no `compose.env`. A configuração padrão é `false`. Essa variável é uma trava operacional; não substitui autorização humana nem inspeção de filas.

O Compose cria dois bancos novos, sem portas no host, dois volumes de worker independentes e um volume de upload. Nenhum container de desenvolvimento é substituído. Apenas a API de vídeos é publicada em `127.0.0.1:18080`. Cada worker tem duas CPUs, 1 GiB de memória e reserva lógica de 3 GiB; verificar espaço físico no Docker antes de executar.

## Iniciar e registrar os vídeos

Com o lote autorizado, em Git Bash:

```bash
dc() { docker compose --env-file "$HARNESS_RUN_DIRECTORY/compose.env" -f compose.aws-harness.yml "$@"; }
dc up -d --wait processing-db video-db
dc --profile execution up -d --wait video
# Fazer uploads pela API real antes de iniciar os workers.
```

Use o contrato multipart de `POST /videos`, idempotency key exclusiva e token da conta descartável. Conserve os recibos no diretório privado do run. Exporte **o envelope persistido** da outbox de vídeos para um arquivo UTF-8, sem reconstruir IDs ou timestamps, e registre antes de iniciar consumidores:

```bash
python scripts/aws_harness.py record-request --run "$HARNESS_RUN_DIRECTORY" --file request.json
```

O registro preserva o envelope, SHA-256 do envelope, eventId, dono e chave original. Rejeita dono não registrado, chave de outro vídeo/bucket e sobrescrita de request existente. Guarde também cada intenção/resultado exportado do banco, inclusive tentativas abandonadas e versionIds S3 quando houver versionamento. O índice de requests **não é** um manifesto completo de limpeza: não automatiza descoberta de resultados, verificação de PUT ativo ou exclusão.

Depois de registrar e armar os casos necessários:

```bash
dc --profile execution up -d --wait worker-a worker-b
```

## Pontos de falha

Os controles são específicos para worker, vídeo e fronteira. Cada controle é utilizado uma única vez, inclusive após restart. `pause` espera até `release` ou até 10 minutos; timeout/interrupção causa falha, nunca continuidade silenciosa. `fail` lança falha imediatamente. Um ponto desarmado delega normalmente ao adapter real.

| Ponto | Momento interceptado | Evidência exigida |
| --- | --- | --- |
| `before-download` | Antes de chamar o S3 para baixar o original | Dependência indisponível simulada apenas nessa instância; mídia não iniciada e nenhuma tentativa gasta. Não equivale a indisponibilidade real da AWS. |
| `before-media` | Antes da chamada ao adapter FFmpeg | Pode segurar duas posses para preparar concorrência. A espera não entra no intervalo `media-start`/`media-end`. |
| `after-put` | Depois que o adapter retornou de PUT + leitura de integridade; antes de `jobs.complete` | Banco em RESULT_PENDING_STORAGE, intenção persistida e ZIP íntegro. Matar o container aqui comprova essa fronteira, não um PUT ainda em trânsito. |
| `before-ack` | Imediatamente antes de `DeleteMessage` do receipt atual | Job terminal, inbox concluída e outbox persistida; reentrega não deve executar mídia. Simula falha antes do ACK, não sucesso remoto com resposta perdida. |

```bash
python scripts/aws_harness.py arm --run "$HARNESS_RUN_DIRECTORY" --worker worker-a --video UUID --point after-put --mode pause
```

Para uma mensagem que pode chegar a qualquer worker, armar o mesmo ponto nos dois workers e identificar qual atingiu a fronteira em `control/events/worker-a.tsv` ou `worker-b.tsv`. Cada linha contém somente instante UTC, worker, evento e videoId; não contém receipt handle, token ou envelope. O arquivo `.claimed` sozinho não prova a fronteira: exigir a linha `*-reached` e o estado correspondente no banco/S3. Nunca usar uma espera fixa como prova de commit.

Após conferir a fronteira e registrar o estado, executar **uma** das ações autorizadas:

```bash
python scripts/aws_harness.py release --run "$HARNESS_RUN_DIRECTORY" --worker worker-a --video UUID --point after-put
# OU, para crash abrupto do worker identificado no evento:
dc kill -s SIGKILL worker-a
# Aguardar recuperação pelo outro worker e conferir SQL/S3/outbox antes de reiniciar.
dc start worker-a
```

Para medir concorrência, pareie `media-start`/`media-end` de dois videoIds distintos em workers distintos. A interseção deve ter duração positiva. Complete essa evidência com tentativas SQL, resultado e ZIPs íntegros. Duas linhas de início ou espera no gate não aprovam o caso. Para duplicação, reenviar somente o arquivo de request registrado e preservar eventId; não alterar token ou lease manualmente.

Os testes do harness verificam pausa/liberação, uso único, alvo/receipt corretos, ausência de evidência falsa após falha no armazenamento e retomada com PostgreSQL real. S3/SQS permanecem simulados nesses testes locais. Queda durante PUT, commit com resposta perdida, reconciliação por remoção de mensagens, conta inativa e DLQ/redrive exigem os procedimentos e evidências adicionais do plano; esses quatro ganchos não aprovam todos os cenários automaticamente. Alarmes e destino operacional ainda devem ser concretizados antes do lote DLQ.

## Encerrar

Parar apenas `video`, `worker-a` e `worker-b` deste projeto Compose. Antes de limpar, conferir ausência de PUT ativo, preservar objetos incertos e consolidar todas as chaves/versionIds do run. Limpeza S3/mensagens permanece manual e limitada ao manifesto revisado; não usar `PurgeQueue`, exclusão recursiva ou remover carga alheia. Preservar bancos e volumes para evidências até essa revisão. `dc down` remove containers/rede e preserva os volumes; não usar `down -v` antes da conferência final.
