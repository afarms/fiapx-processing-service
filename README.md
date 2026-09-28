# FIAP X — Processamento

Serviço independente para processamento assíncrono de vídeos. A base atual fornece aplicação Spring Boot, conexão PostgreSQL/Liquibase, probes de saúde e configuração validada dos limites. Consumo SQS, jobs persistidos, FFmpeg, ZIP, armazenamento S3 e eventos de resultado ainda estão em implementação. Esta imagem ainda não inclui FFmpeg e não consome mensagens.

## Desenvolvimento local

Java 21, Maven Wrapper 3.9.16, Spring Boot 4.1.1, GNU Make e Git Bash no Windows. Use `make verify` para build limpo, testes e JaCoCo (mínimo de 90% de linhas e branches). `make install` também instala o artefato no cache Maven. O teste de arquitetura compila o core somente com o JDK, sem dependências Spring/JPA. Beans próprios são montados em `infrastructure/config/BeanConfig`.

Copie `.env.example` para `.env` e configure senha local exclusiva. O banco de processamento é independente dos bancos de vídeos e identidade. Não compartilhe credenciais entre os serviços.

```bash
make verify
make config-check
make up
```

Compose inicia PostgreSQL 17 e a aplicação. Banco publicado apenas em localhost:5434 e aplicação em localhost:8082 por padrão; APP_PORT e POSTGRES_PORT permitem alterar o mapeamento. `make down` preserva os volumes. `make run` usa o banco informado no `.env`, sem iniciar container; `make image` constrói a imagem separadamente.

Depois de `make up`, `make integration-bootstrap` verifica probes reais, interrompe somente o banco deste projeto e o restaura automaticamente. Readiness deve passar de UP para HTTP 503 e voltar a UP; liveness deve permanecer UP. O teste não acessa AWS nem remove dados/volumes.

A imagem usa JDK no build e JRE no runtime, usuário não-root, filesystem raiz somente leitura e diretório de trabalho dedicado. O Dockerfile empacota com `-DskipTests`; CI executa testes/cobertura antes de construir a imagem. A CI está preparada, sem evidência de execução remota ainda.

## Saúde e limites

Sem endpoints públicos de negócio. `GET /actuator/health/liveness` informa vida do processo; `GET /actuator/health/readiness` inclui banco. Probes não retornam detalhes internos e devem permanecer na rede interna quando implantadas. Nenhum endpoint de upload ou consulta de usuários é servido aqui.

| Parâmetro | Valor inicial |
| --- | --- |
| Entrada | 100.000.000 bytes, 300 segundos de mídia |
| PNGs somados / ZIP | 1 GiB cada |
| Tempo de ffprobe/FFmpeg por tentativa | 600 segundos |
| Tentativas totais de processamento | 3, independentes de recebimentos SQS |
| Reserva de disco por execução | 3 GiB |
| Lease / heartbeat | 120 / 30 segundos |

Os valores estão configurados e validados na inicialização; a imposição durante processamento será implementada com o pipeline de mídia. Configuração inválida impede inicialização, incluindo heartbeat maior ou igual ao lease ou reserva que não comporte entrada, PNGs, ZIP e margem. A reserva configurada não cria uma quota de disco por si só. O Compose limita a aplicação a 1 GiB de RAM e duas CPUs.

Liquibase possui changelog raiz preparado; ainda não há tabelas de jobs. Hibernate usa `validate`, nunca `update`. PostgreSQL e FFmpeg reais serão usados para validar persistência e processamento conforme essas funcionalidades forem entregues.

## Integração planejada

Receber VideoProcessingRequested, persistir job e produzir ProcessingStarted/Completed/Failed. ACK manual somente após efeito durável; resultado e outbox na mesma transação, deduplicação para redelivery. Inativação de conta não cancela trabalho previamente aceito. O serviço de vídeos permanece responsável pelas consultas e autorização de acesso. Não executar contra filas reais até consumidores, permissões e recuperação estarem prontos e verificados.
