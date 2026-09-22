package br.com.conde.arvoreb.arvore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.conde.arvoreb.pagina.Arquivo;
import br.com.conde.arvoreb.pagina.Pagina;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O juiz destes testes é o {@link TreeMap}.
 *
 * <p>Ele já é um mapa ordenado, já está certo e já está na biblioteca padrão.
 * Fazer as mesmas dezenas de milhares de operações nos dois e exigir o mesmo
 * resultado vale mais do que qualquer teste que eu inventasse — inclusive
 * porque pega os casos que eu não pensaria em escrever.
 */
@DisplayName("árvore B+ em disco")
class ArvoreBTest {

  @Nested
  @DisplayName("o básico")
  class Basico {

    @Test
    @DisplayName("guarda e devolve")
    void guardaEDevolve(@TempDir Path pasta) throws IOException {
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        arvore.colocar("nome", "Ana");
        arvore.colocar("cidade", "Recife");

        assertThat(arvore.obter("nome")).contains("Ana");
        assertThat(arvore.obter("cidade")).contains("Recife");
        assertThat(arvore.obter("nada")).isEmpty();
        assertThat(arvore.tamanho()).isEqualTo(2);
      }
    }

    @Test
    @DisplayName("a árvore nova está vazia e tem altura zero")
    void novaEstaVazia(@TempDir Path pasta) throws IOException {
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        assertThat(arvore.vazia()).isTrue();
        assertThat(arvore.altura()).isZero();
        assertThat(arvore.tudo()).isEmpty();
        assertThat(arvore.remover("qualquer")).isFalse();
      }
    }

    @Test
    @DisplayName("gravar a mesma chave substitui e não conta de novo")
    void substitui(@TempDir Path pasta) throws IOException {
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        arvore.colocar("a", "primeiro");
        arvore.colocar("a", "segundo");

        assertThat(arvore.obter("a")).contains("segundo");
        assertThat(arvore.tamanho()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("a ordem de saída é a das chaves, não a de inserção")
    void saiEmOrdem(@TempDir Path pasta) throws IOException {
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (String chave : List.of("melancia", "abacaxi", "caju", "banana")) {
          arvore.colocar(chave, chave.toUpperCase());
        }

        assertThat(arvore.chaves()).containsExactly("abacaxi", "banana", "caju", "melancia");
      }
    }

    @Test
    @DisplayName("chave nula ou vazia e valor nulo são recusados")
    void entradaInvalida(@TempDir Path pasta) throws IOException {
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        assertThatThrownBy(() -> arvore.colocar(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arvore.colocar("", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arvore.colocar("a", null)).hasMessageContaining("use remover");
      }
    }
  }

  @Nested
  @DisplayName("contra o TreeMap")
  class ContraOTreeMap {

    @Test
    @DisplayName("vinte mil operações sorteadas dão o mesmo resultado")
    void vinteMilOperacoes(@TempDir Path pasta) throws IOException {
      // Semente fixa: um teste que falha uma vez a cada cem execuções é pior
      // do que teste nenhum, porque ninguém consegue reproduzir.
      Random sorteio = new Random(20260922);
      TreeMap<String, String> referencia = new TreeMap<>();

      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 20_000; i += 1) {
          String chave = "chave-" + sorteio.nextInt(3000);

          if (sorteio.nextInt(100) < 30) {
            assertThat(arvore.remover(chave))
                .as("remoção de %s na operação %s", chave, i)
                .isEqualTo(referencia.remove(chave) != null);
          } else {
            String valor = "valor-" + i;

            arvore.colocar(chave, valor);
            referencia.put(chave, valor);
          }

          if (i % 2000 == 0) {
            assertThat(arvore.tamanho()).as("tamanho na operação %s", i).isEqualTo(referencia.size());
          }
        }

        assertThat(arvore.tamanho()).isEqualTo(referencia.size());
        assertThat(arvore.tudo()).containsExactlyEntriesOf(referencia);
      }
    }

    @Test
    @DisplayName("as buscas também batem, uma a uma")
    void buscasBatem(@TempDir Path pasta) throws IOException {
      Random sorteio = new Random(7);
      TreeMap<String, String> referencia = new TreeMap<>();

      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 3000; i += 1) {
          String chave = "k" + sorteio.nextInt(5000);
          String valor = "v" + i;

          arvore.colocar(chave, valor);
          referencia.put(chave, valor);
        }

        for (int i = 0; i < 5000; i += 1) {
          String chave = "k" + i;

          assertThat(arvore.obter(chave).orElse(null)).as("busca de %s", chave).isEqualTo(referencia.get(chave));
        }
      }
    }

    @Test
    @DisplayName("os intervalos batem com os do subMap")
    void intervalosBatem(@TempDir Path pasta) throws IOException {
      TreeMap<String, String> referencia = new TreeMap<>();

      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 2000; i += 1) {
          String chave = String.format("%05d", i);

          arvore.colocar(chave, "v" + i);
          referencia.put(chave, "v" + i);
        }

        for (String[] faixa : new String[][] {{"00010", "00020"}, {"00000", "00000"}, {"01990", "01999"}, {"00500", "01500"}}) {
          Map<String, String> esperado = referencia.subMap(faixa[0], true, faixa[1], true);

          assertThat(arvore.intervalo(faixa[0], faixa[1]))
              .as("intervalo %s..%s", faixa[0], faixa[1])
              .containsExactlyEntriesOf(esperado);
        }

        assertThat(arvore.intervalo("09999", "99999")).isEmpty();
        assertThat(arvore.intervalo(null, "00002")).hasSize(3);
      }
    }

    @Test
    @DisplayName("chaves de tamanhos muito diferentes não quebram a divisão")
    void chavesDeTamanhosVariados(@TempDir Path pasta) throws IOException {
      // A divisão é por bytes, não por contagem: é exatamente aqui que uma
      // implementação que conta chaves estoura a página.
      Random sorteio = new Random(99);
      TreeMap<String, String> referencia = new TreeMap<>();

      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 1500; i += 1) {
          String chave = "c" + i + "-".repeat(sorteio.nextInt(60));
          String valor = "v".repeat(1 + sorteio.nextInt(300));

          arvore.colocar(chave, valor);
          referencia.put(chave, valor);
        }

        assertThat(arvore.tudo()).containsExactlyEntriesOf(referencia);
      }
    }

    @Test
    @DisplayName("apagar tudo deixa a árvore vazia de novo")
    void apagarTudo(@TempDir Path pasta) throws IOException {
      List<String> chaves = new ArrayList<>();

      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 2000; i += 1) {
          String chave = String.format("%05d", i);

          chaves.add(chave);
          arvore.colocar(chave, "v");
        }

        java.util.Collections.shuffle(chaves, new Random(3));

        for (String chave : chaves) {
          assertThat(arvore.remover(chave)).as("removendo %s", chave).isTrue();
        }

        assertThat(arvore.tamanho()).isZero();
        assertThat(arvore.tudo()).isEmpty();
        assertThat(arvore.altura()).isZero();

        // E ainda dá para usar depois de esvaziar.
        arvore.colocar("depois", "ok");

        assertThat(arvore.obter("depois")).contains("ok");
      }
    }
  }

  @Nested
  @DisplayName("a árvore em disco")
  class EmDisco {

    @Test
    @DisplayName("fechar e reabrir devolve tudo")
    void reabrir(@TempDir Path pasta) throws IOException {
      Path caminho = pasta.resolve("a.db");
      TreeMap<String, String> referencia = new TreeMap<>();

      try (ArvoreB arvore = ArvoreB.abrir(caminho)) {
        for (int i = 0; i < 5000; i += 1) {
          String chave = String.format("%06d", (i * 7919) % 5000);

          arvore.colocar(chave, "valor-" + i);
          referencia.put(chave, "valor-" + i);
        }
      }

      try (ArvoreB reaberta = ArvoreB.abrir(caminho)) {
        assertThat(reaberta.tamanho()).isEqualTo(referencia.size());
        assertThat(reaberta.tudo()).containsExactlyEntriesOf(referencia);
      }
    }

    @Test
    @DisplayName("a árvore fica rasa mesmo com muita chave")
    void ficaRasa(@TempDir Path pasta) throws IOException {
      // É o motivo de a estrutura existir: 20 mil chaves em três ou quatro
      // níveis, e não nos vinte de uma árvore binária.
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 20_000; i += 1) {
          arvore.colocar(String.format("%08d", i), "v");
        }

        assertThat(arvore.altura()).isBetween(2, 4);
      }
    }

    @Test
    @DisplayName("uma busca toca poucas páginas")
    void buscaTocaPoucasPaginas(@TempDir Path pasta) throws IOException {
      Path caminho = pasta.resolve("a.db");

      try (ArvoreB arvore = ArvoreB.abrir(caminho)) {
        for (int i = 0; i < 20_000; i += 1) {
          arvore.colocar(String.format("%08d", i), "v" + i);
        }
      }

      try (ArvoreB arvore = ArvoreB.abrir(caminho, 0)) {
        arvore.arquivo().esquecer();

        long antes = arvore.arquivo().leiturasDeDisco();

        arvore.obter("00010000");

        long paginasLidas = arvore.arquivo().leiturasDeDisco() - antes;

        // Uma por nível, e nada mais. Numa árvore binária seriam ~15.
        assertThat(paginasLidas).isLessThanOrEqualTo(4);
      }
    }

    @Test
    @DisplayName("o arquivo não é aberto como se fosse outra coisa")
    void arquivoDeOutroTipo(@TempDir Path pasta) throws IOException {
      Path caminho = pasta.resolve("qualquer.bin");

      Files.write(caminho, new byte[Pagina.TAMANHO]);

      assertThatThrownBy(() -> ArvoreB.abrir(caminho))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("não é uma árvore B");
    }

    @Test
    @DisplayName("o encadeamento das folhas sobrevive às divisões")
    void encadeamentoSobrevive(@TempDir Path pasta) throws IOException {
      // Se uma divisão esquecer de refazer o encadeamento, a varredura pula a
      // metade nova — e o erro só aparece aqui, não numa busca por chave.
      try (ArvoreB arvore = ArvoreB.abrir(pasta.resolve("a.db"))) {
        for (int i = 0; i < 5000; i += 1) {
          arvore.colocar(String.format("%06d", i), "v");
        }

        assertThat(arvore.tudo()).hasSize(5000);
        assertThat(arvore.chaves()).isSorted();
      }
    }

    @Test
    @DisplayName("remover muito não deixa o arquivo cheio de páginas vazias")
    void removerFunde(@TempDir Path pasta) throws IOException {
      Path caminho = pasta.resolve("a.db");

      try (ArvoreB arvore = ArvoreB.abrir(caminho)) {
        for (int i = 0; i < 5000; i += 1) {
          arvore.colocar(String.format("%06d", i), "v".repeat(50));
        }

        int folhasCheias = arvore.folhasEmUso();

        assertThat(folhasCheias).isGreaterThan(50);

        for (int i = 0; i < 4900; i += 1) {
          arvore.remover(String.format("%06d", i));
        }

        assertThat(arvore.tamanho()).isEqualTo(100);

        // Sem fusão sobrariam as mesmas ~70 folhas, todas quase vazias, e o
        // arquivo nunca mais encolheria. Com ela, as 100 chaves que restaram
        // cabem em duas ou três.
        assertThat(arvore.folhasEmUso()).isLessThan(folhasCheias / 10);
      }
    }
  }

  @Nested
  @DisplayName("a página")
  class DaPagina {

    @Test
    @DisplayName("vai e volta pelo bloco")
    void vaiEVolta() {
      Pagina folha = new Pagina(3, Pagina.FOLHA);

      folha.chaves().add("a");
      folha.valores().add("primeiro");
      folha.chaves().add("b");
      folha.valores().add("segundo");
      folha.proxima(9);

      Pagina lida = Pagina.de(3, folha.bytes());

      assertThat(lida.ehFolha()).isTrue();
      assertThat(lida.chaves()).containsExactly("a", "b");
      assertThat(lida.valores()).containsExactly("primeiro", "segundo");
      assertThat(lida.proxima()).isEqualTo(9);
    }

    @Test
    @DisplayName("um nó interno com n chaves tem n+1 filhos")
    void internaTemUmFilhoAMais() {
      Pagina interna = new Pagina(1, Pagina.INTERNA);

      interna.chaves().add("m");
      interna.filhos().add(2);
      interna.filhos().add(3);

      Pagina lida = Pagina.de(1, interna.bytes());

      assertThat(lida.chaves()).hasSize(1);
      assertThat(lida.filhos()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("o bloco tem sempre 4096 bytes, cheio ou vazio")
    void blocoDeTamanhoFixo() {
      assertThat(new Pagina(1, Pagina.FOLHA).bytes()).hasSize(Pagina.TAMANHO);
    }

    @Test
    @DisplayName("acentos contam em bytes, não em caracteres")
    void acentosContamEmBytes() {
      Pagina folha = new Pagina(1, Pagina.FOLHA);

      folha.chaves().add("ção");
      folha.valores().add("");

      // 3 caracteres, 5 bytes: contar caracteres subestimaria a ocupação e a
      // página estouraria na gravação.
      assertThat(folha.bytesOcupados()).isEqualTo(Pagina.CABECALHO + 2 + 5 + 2);
      assertThat(Pagina.de(1, folha.bytes()).chaves()).containsExactly("ção");
    }

    @Test
    @DisplayName("página que não cabe reclama em vez de truncar")
    void naoCabeReclama() {
      Pagina folha = new Pagina(1, Pagina.FOLHA);

      folha.chaves().add("k");
      folha.valores().add("v".repeat(Pagina.TAMANHO));

      assertThat(folha.cabe()).isFalse();
      assertThatThrownBy(folha::bytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a busca binária acha a posição de inserção")
    void buscaBinaria() {
      Pagina folha = new Pagina(1, Pagina.FOLHA);

      for (String chave : List.of("b", "d", "f")) {
        folha.chaves().add(chave);
        folha.valores().add("x");
      }

      assertThat(folha.procurar("a")).isZero();
      assertThat(folha.procurar("b")).isZero();
      assertThat(folha.procurar("c")).isEqualTo(1);
      assertThat(folha.procurar("g")).isEqualTo(3);
      assertThat(folha.contem("d")).isTrue();
      assertThat(folha.contem("e")).isFalse();
    }

    @Test
    @DisplayName("tipo desconhecido no bloco é recusado")
    void tipoDesconhecido() {
      byte[] bloco = new byte[Pagina.TAMANHO];

      bloco[0] = 42;

      assertThatThrownBy(() -> Pagina.de(1, bloco)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("página fora do arquivo é recusada")
    void paginaForaDoArquivo(@TempDir Path pasta) throws IOException {
      try (Arquivo arquivo = Arquivo.abrir(pasta.resolve("a.db"), 8)) {
        assertThatThrownBy(() -> arquivo.ler(99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arquivo.ler(0)).isInstanceOf(IllegalArgumentException.class);
      }
    }
  }
}
