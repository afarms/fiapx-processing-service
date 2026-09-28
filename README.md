# FIAP X — Processamento

Serviço independente para processamento assíncrono de vídeos. Implementados bootstrap, probes, persistência transacional de jobs/tentativas/inbox/outbox e pipeline local FFprobe/FFmpeg para PNGs em ZIP, com limites durante a produção. A imagem inclui FFmpeg 8.1.2; consumo/publicação SQS e armazenamento S3 ainda estão em implementação. Ver [contrato de persistência](docs/persistence.md) e [pipeline de mídia](docs/media.md).

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

A imagem usa JDK no build e JRE no runtime, usuário não-root, filesystem raiz somente leitura e diretório de trabalho dedicado. O Dockerfile empacota com `-DskipTests`; CI executa testes/cobertura antes de construir a imagem. Integrações com banco e mídia são executadas separadamente, por Makefile.

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

O pipeline local impõe tamanho de entrada, duração decodificada, bytes agregados de PNG e ZIP e prazo compartilhado entre FFprobe/FFmpeg. Configuração inválida impede inicialização, incluindo mais de três tentativas, heartbeat maior ou igual ao lease ou reserva que não comporte entrada, PNGs, ZIP e margem. Uma execução por instância verifica espaço livre antes de iniciar; a reserva configurada não cria uma quota de filesystem. O Compose limita a aplicação a 1 GiB de RAM e duas CPUs. O consumidor futuro será responsável por admissão antes de receber trabalho e renovar a posse.

Liquibase gerencia as tabelas de jobs, tentativas, inbox, intenções de resultado e outbox. Hibernate usa `validate`, nunca `update`. `make integration` verifica persistência/concorrência com PostgreSQL real em schemas isolados do banco local informado no `.env`. `make integration-media` executa testes com FFmpeg real em container limitado a 1 GiB e duas CPUs, sem depender do FFmpeg do host. Ambos são locais e não acessam AWS.

## Integração planejada

O gateway já persiste jobs e envelopes ProcessingStarted/Completed/Failed. Próxima integração: receber VideoProcessingRequested via SQS, executar mídia e publicar a outbox. ACK manual somente após efeito durável; resultado e outbox na mesma transação, deduplicação para redelivery. Inativação de conta não cancela trabalho previamente aceito. O serviço de vídeos permanece responsável pelas consultas e autorização de acesso. Não executar contra filas reais até consumidores, permissões e recuperação estarem prontos e verificados.
