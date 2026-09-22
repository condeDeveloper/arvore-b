# arvore-b

Uma árvore B+ em disco escrita do zero em Java 21: páginas de tamanho fixo,
divisão, fusão, folhas encadeadas e persistência. É a estrutura que está por
baixo de todo índice de banco de dados relacional.

O juiz dos testes é o `TreeMap` da biblioteca padrão — vinte mil operações
sorteadas nos dois, exigindo o mesmo resultado.

```
chaves:  200000
altura:  3
folhas:  2406
arquivo: 9696 KB

busca de 00100000 -> cidade-100000
paginas lidas do disco: 3

intervalo 00099998..00100002 -> 5 entradas
paginas lidas do disco: 3
  00099998 = cidade-99998
  00099999 = cidade-99999
  00100000 = cidade-100000
  00100001 = cidade-100001
  00100002 = cidade-100002
```

Duzentas mil chaves, **três** leituras de disco para achar qualquer uma.

## Por que existe

### 1. O nó tem o tamanho de um bloco de disco

Essa é a decisão que define a estrutura, e ela não tem nada a ver com
algoritmo: tem a ver com hardware.

Uma árvore binária balanceada com um milhão de chaves tem uns 20 níveis. Se
cada nível for uma ida ao disco, são 20 idas. Uma árvore B com páginas de
4 KB guarda centenas de chaves por nó, tem 3 ou 4 níveis para o mesmo milhão,
e faz 3 ou 4 idas.

**Não é a quantidade de comparações que importa; é a quantidade de vezes que
se atravessa a fronteira entre memória e disco.** Dentro da página, a busca
binária resolve 200 chaves em 8 comparações que não custam nada.

Por isso a página tem 4096 bytes: é o tamanho do bloco na maioria dos sistemas
de arquivos, e ler menos custa o mesmo que ler isso.

### 2. B**+**, e não B

Os valores ficam **só nas folhas**; os nós internos guardam apenas fronteiras.
Duas consequências, e as duas são o motivo de bancos de dados usarem B+:

- Os nós internos cabem muito mais chaves, porque não carregam valor nenhum.
  A árvore fica mais rasa e cada busca toca menos páginas.
- As folhas são **encadeadas**. Varrer um intervalo é caminhar numa lista, sem
  voltar para a raiz a cada passo — é por isso que
  `WHERE id BETWEEN 10 AND 20` é barato num índice, e por que ele responde em
  tempo proporcional ao **tamanho da faixa**, não ao da tabela.

### 3. A árvore cresce pela raiz

Quando a raiz enche, ela divide e uma raiz nova aparece **em cima**. É o único
jeito de a altura aumentar — e é o que mantém a árvore perfeitamente
equilibrada sem uma única rotação. Todas as folhas estão sempre à mesma
distância da raiz, de graça.

Na remoção acontece o contrário: quando a raiz interna fica sem chave nenhuma,
ela some e o único filho vira a raiz. A árvore encolhe pelo mesmo lugar por
onde cresceu.

### 4. Dividir por bytes, não por contagem

A árvore B de livro-texto divide quando o nó chega a `2t-1` chaves. Isso só
funciona quando toda chave tem o mesmo tamanho.

Com texto de tamanho variável, **três chaves gigantes enchem a página e
cinquenta minúsculas não**. Aqui a conta é de bytes ocupados, e a divisão
corta onde as duas metades ficam parecidas em bytes. Há um teste que insere
chaves de 1 a 60 caracteres com valores de 1 a 300 e compara com o `TreeMap` —
é exatamente ali que uma implementação que conta chaves estoura a página.

E acento conta em **bytes**: `ção` tem 3 caracteres e 5 bytes.

### 5. A fronteira é a menor chave da direita

Num nó interno, `chaves[i]` é a menor chave da subárvore `filhos[i+1]`. Então
uma chave **igual** à fronteira vai para a direita, não para a esquerda.

Errar isso produz o bug mais silencioso que esta estrutura tem: a árvore
funciona para quase tudo e não acha exatamente as chaves que por acaso viraram
separador.

## A API

```java
try (ArvoreB arvore = ArvoreB.abrir(Path.of("indice.db"))) {
  arvore.colocar("cidade", "Recife");

  arvore.obter("cidade");            // Optional[Recife]
  arvore.contem("cidade");           // true
  arvore.remover("cidade");          // true

  arvore.intervalo("a", "m");        // Map em ordem, pelo encadeamento
  arvore.chaves();                   // todas, ordenadas
  arvore.tudo();

  arvore.tamanho();
  arvore.altura();
  arvore.folhasEmUso();
  arvore.arquivo().leiturasDeDisco();
}
```

## O formato em disco

```
página 0       cabeçalho: assinatura "BTRE", tamanho da página, raiz, total

página n       byte   tipo        0 = folha, 1 = interna
               short  quantidade
               int    proxima     só na folha: a folha seguinte
               ...    entradas
```

A página `n` começa no byte `n * 4096` — não há índice de páginas, a
aritmética resolve. O cabeçalho guarda qual página é a raiz; sem ele, reabrir
o arquivo não saberia por onde começar.

## Estrutura

```
pagina/Pagina.java    o bloco: formato, busca binária, ocupação em bytes
pagina/Arquivo.java   o vetor de blocos, o cabeçalho e o cache LRU
arvore/ArvoreB.java   busca, divisão, fusão, empréstimo e varredura
```

O cache é um LRU de páginas, e ele importa mais do que parece: numa árvore de
três níveis, a raiz é lida em **toda** operação.

## Rodando

```bash
mvn test
```

24 testes. Os cinco que mais valem usam o `TreeMap` como oráculo:

- **vinte mil operações sorteadas** (70% gravação, 30% remoção) nos dois, com
  semente fixa, conferindo tamanho no caminho e o conteúdo inteiro no fim;
- cinco mil buscas, uma a uma, comparadas com `TreeMap.get`;
- intervalos comparados com `TreeMap.subMap`;
- chaves e valores de tamanhos muito variados, que é onde a divisão por bytes
  se prova;
- apagar tudo em ordem embaralhada e exigir que a árvore volte a ficar vazia
  e utilizável.

Mais um que conta as **páginas lidas do disco** numa busca com o cache
desligado e exige no máximo quatro.

Java 21.

## Limites conhecidos

- **Sem lista de páginas livres.** Uma página que ficou vazia depois de uma
  fusão continua ocupando lugar no arquivo: o arquivo nunca encolhe, só para
  de crescer. Um banco de verdade tem um *free list*.
- **Sem concorrência.** Uma instância por arquivo, uma operação por vez. Não
  há trava de página nem controle de versão — que é o assunto inteiro de um
  banco de dados de verdade.
- **Sem durabilidade transacional.** `sincronizar()` existe, mas uma divisão
  toca várias páginas e uma queda no meio deixa a árvore inconsistente. É para
  isso que existe *write-ahead log*.
- **Chave e valor são texto.** Não há tipos, nem comparador configurável: a
  ordem é a de `String.compareTo`, então `"10"` vem antes de `"9"`.
- **Uma entrada precisa caber numa página.** Chave e valor somados acima de
  ~4 KB fazem a gravação reclamar; bancos de verdade guardam o excedente em
  páginas de estouro.
- O `tamanho()` é recontado na abertura, varrendo as folhas. Guardá-lo no
  cabeçalho custaria uma escrita a mais por operação.

## Licença

MIT.
