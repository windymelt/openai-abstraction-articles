# openai-abstraction-articles

## Usage

```sh
curl https://api.github.com/repos/slick/slick/releases \
  | jq '.[0:3] | .[] | {title: .name, body: .body}' \
  | OPENAI_API_KEY=sk-**** OPENAI_ORG=org-**** scala-cli ./abstraction.scala \
  -- \
  --domain "ScalaのライブラリSlick" --role "ソフトウェアエンジニア" --subject "Release note" -n 10
```
