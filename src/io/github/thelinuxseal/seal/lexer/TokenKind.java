package io.github.thelinuxseal.seal.lexer;

public enum TokenKind {
    // Literals and names
    IDENTIFIER,
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    CHAR_LITERAL,   // 'a': an integer constant, the character's UTF-16 code

    // Declaration keywords
    STRUCT, UNIQUE, CLASS, INTERFACE, ENUM, ABSTRACT, EXTENDS, IMPLEMENTS, PUBLIC, PRIVATE, STATIC, CONST, PURE,
    PACKAGE, IMPORT,

    // Primitive type keywords
    INT, UINT, BIT, CHAR, FLOAT, DOUBLE, VOID, ADDR, HANDLE, FIXED, UFIXED,

    // Statement / expression keywords
    NEW, DELETE, RETURN, IF, ELSE, WHILE, FOR, BREAK, CONTINUE, SWITCH, CASE, DEFAULT, ARROW,
    THROW, TRY, CATCH, FINALLY, ASSERT,
    SIZEOF, ALIGNOF, SUPER, THIS, NULL, TRUE, FALSE, INSTANCEOF,

    // Brackets
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,

    // Angle brackets. `<<` always opens a value list. `>>` closes a value
    // list, or two type-argument lists (`Array<addr<int>>`); the parser
    // splits it into two GT tokens when it needs a single `>`.
    LT, GT,
    DOUBLE_LT, // <<
    DOUBLE_GT, // >>

    // Punctuation
    SEMICOLON, COMMA, DOT, DOT_DOT, AT, COLON, COLON_COLON, QUESTION,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    STAR_STAR,    // ** (power)
    PLUS_PLUS, MINUS_MINUS,
    AMP, PIPE, CARET, TILDE, BANG,
    AMP_AMP, PIPE_PIPE,
    ASSIGN, EQ, NEQ, LE, GE,
    SHIFT,        // <<<
    SHIFT_ASSIGN, // <<=
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN, STAR_STAR_ASSIGN,
    AMP_ASSIGN, PIPE_ASSIGN, CARET_ASSIGN,

    ERROR,
    EOF
}
