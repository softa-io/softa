// The generated lexer/parser under gen/ are committed, and there is no build plugin, so a change
// here needs the ANTLR tool run by hand at the version softa-orm depends on:
//
//   curl -O https://repo1.maven.org/maven2/org/antlr/antlr4/4.13.2/antlr4-4.13.2-complete.jar
//   java -jar antlr4-4.13.2-complete.jar -visitor -no-listener \
//        -package io.softa.framework.orm.domain.antlr.gen -o <tmp> FilterExpr.g4
//
// then copy the result into gen/, dropping the `// Generated from <abs path>` first line — it
// embeds whoever's checkout produced it. Regeneration is reproducible: the serialized ATN of an
// unchanged grammar comes out byte-identical.

grammar FilterExpr;

expr:   expr AND expr               # AndExpr
    |   expr OR expr                # OrExpr
    |   '(' expr ')'                # ParenExpr
    |   unit                        # UnitExpr
    ;

AND:    'AND';
OR:     'OR';

unit:   FIELD OPERATOR value        # FilterUnitExpr
    ;

value: singleValue                  # SingleValueExpr
     | listValue                    # ListValueExpr
     ;

// A bare name is another field of the same row; a literal always carries its quotes. Unambiguous
// because the two never look alike, and safe in both directions of typo: a bare name that is not a
// field fails the boot-time existence check, and a literal written without its quotes is either an
// unknown field or — option codes being PascalCase — not a FIELD token at all.
singleValue: NUMBER
           | BOOLEAN
           | QUOTED_STRING
           | FIELD
           ;

listValue: '[' singleValue (',' singleValue)* ']'
          ;

// Declared before FIELD on purpose: ANTLR breaks an equal-length tie by declaration order, so with
// FIELD first `true` lexed as a field name and `hasProbation != true` never parsed. Harmless while a
// bare name was not a legal value — it was a syntax error — but now it would quietly mean "the field
// named true".
BOOLEAN: 'true' | 'false';

FIELD:  [a-z][a-zA-Z0-9]*;
OPERATOR: '='
        | '!='
        | '>'
        | '>='
        | '<'
        | '<='
        | 'CONTAINS'
        | 'NOT CONTAINS'
        | 'START WITH'
        | 'NOT START WITH'
        | 'IN'
        | 'NOT IN'
        | 'BETWEEN'
        | 'NOT BETWEEN'
        | 'IS SET'
        | 'IS NOT SET'
        | 'PARENT OF'
        | 'CHILD OF';

NUMBER: [0-9]+ ('.' [0-9]+)?;
QUOTED_STRING: '"' (~["\\] | '\\' .)* '"';  // Double-quoted string, supports escape characters

WS: [ \t\r\n]+ -> skip;                     // Ignore whitespace