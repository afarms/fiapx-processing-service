# Armazenamento e recuperação

`StoreProcessingResult` implementa a fase de armazenamento. O chamador controla admissão, início da tentativa de mídia, heartbeat, retentativas e ACK. O download do original confere tamanho e SHA-256 antes de disponibilizar o arquivo; erro de leitura ou integridade remove apenas o download parcial criado pela chamada.

## Escrita e confirmação

O ZIP completo é copiado para o volume local, conferido, sincronizado com `force(true)` e renomeado atomicamente antes de persistir a intenção SQL. Somente depois ocorre o PUT em `results/{ownerId}/{videoId}/{producerToken}/frames.zip`. O token identifica quem produziu o arquivo e permanece na chave quando outra execução assume a recuperação.

O PUT usa `If-None-Match: *` e checksum SHA-256. Conflitos 409/412 levam à conferência do objeto existente, sem sobrescrita. A confirmação lê o conteúdo com memória limitada, verifica tamanho e hash e só então conclui job/inbox/outbox na mesma transação. A cópia local é removida depois do retorno do commit. Falhas de PUT, leitura ou commit não apagam o objeto remoto.

Na recuperação, verifica primeiro S3. Apenas `404 NoSuchKey` significa ausência; acesso negado, bucket ausente, resposta sem código conhecido, falha de rede ou conteúdo divergente são erros operacionais. Se o objeto estiver ausente, reutiliza o ZIP local íntegro. Se ambos estiverem ausentes, descarta a intenção e expira a posse: uma nova execução precisa adquirir outro token/chave. Não executa FFmpeg dentro desta fase.

## Volume e limpeza

Cada instância precisa de volume exclusivo, persistente entre reinícios do processo para aproveitar a cópia local. Um lock de arquivo impede abertura simultânea do mesmo diretório. Os arquivos ficam em diretórios por job/token; arquivos desconhecidos e links simbólicos não são apagados arbitrariamente. Perda do volume elimina essa via de recuperação, mas o objeto S3 ainda é consultado primeiro. A sincronização do arquivo não representa teste de durabilidade contra falha física do disco.

`CleanupProcessingArtifacts` é uma operação explícita, ainda sem agendamento. A elegibilidade adquire o lock da linha do job e protege produtores ativos e resultados referenciados. Nunca apaga o resultado remoto vencedor. Após terminal durável, a cópia local pode ser removida. Intenções abandonadas permanecem disponíveis para novas varreduras, permitindo remover PUTs tardios; uma migration adicional registra a última verificação e alterna os lotes. A limpeza não apaga originais no S3.

## Configuração e verificação

Armazenamento fica desabilitado por padrão (`PROCESSING_STORAGE_ENABLED=false`). Ao habilitar, configure `PROCESSING_MEDIA_BUCKET`, `AWS_REGION` e credenciais pela cadeia padrão do SDK ou `PROCESSING_AWS_PROFILE` na execução local. O Compose não monta credenciais do host. `PROCESSING_ARTIFACT_DIRECTORY` usa `/app/.local/artifacts`; o Compose persiste `/app/.local` no volume da instância. Habilitar os beans não inicia consumo de filas nem um scheduler.

`make verify` verifica integridade, limites, erros S3, PUT condicional e filesystem local, usando mocks do cliente S3. `make integration` verifica migrations, concorrência entre intenção/limpeza, recuperação com PostgreSQL real e reabertura do volume local; S3 continua simulado. A integração com AWS real ainda não foi validada neste incremento.

Referências: [PutObject e escrita condicional](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html), [GetObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).
