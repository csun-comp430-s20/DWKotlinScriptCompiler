package com.github.wangdong20.kotlinscriptcompiler.parser;

import com.github.wangdong20.kotlinscriptcompiler.token.*;

import java.util.LinkedHashMap;

public class Parser {

    private final Token[] tokens;

    public Parser(final Token[] tokens) {
        this.tokens = tokens;
    }

    private class ParseResult<A> {
        public final A result;
        public final int nextPos;

        public ParseResult(A result, int nextPos) {
            this.result = result;
            this.nextPos = nextPos;
        }
    }

    private void checkTokenIs(final int position, final Token token) throws ParseException {
        final Token tokenHere = readToken(position);
        if (!tokenHere.equals(token)) {
            throw new ParseException("Expected: " + token.toString() +
                    "Received: " + tokens[position].toString());
        }
    }

    private Token checkTokenIsOr(final int position, final Token... token) throws ParseException {
        for(Token t : token) {
            final Token tokenHere = readToken(position);
            if (tokenHere.equals(t)) {
               return t;
            }
        }
        throw new ParseException("Expected: " + token.toString() +
                "\nReceived: " + tokens[position].toString());
    }

    private Token readToken(final int position) throws ParseException {
        if (position < tokens.length) {
            return tokens[position];
        } else {
            throw new ParseException("Position out of bounds: " + position);
        }
    } // readToken

    public ParseResult<Exp> parseAdditiveExpHelper(final int startPos, final Exp leftExp) {
        int curPos = startPos;
        Exp resultExp = leftExp;

        while(curPos < tokens.length) {
            try {
                Token t = checkTokenIsOr(curPos, BinopToken.TK_PLUS, BinopToken.TK_MINUS);
                final ParseResult<Exp> curPrimary = parsePrimary(curPos + 1);
                curPos = curPrimary.nextPos;
                resultExp = new AdditiveExp(resultExp, curPrimary.result, (t == BinopToken.TK_PLUS) ? AdditiveOp.EXP_PLUS :
                        AdditiveOp.EXP_MINUS);
            } catch (ParseException e) {
                break;
            }
        }
        return new ParseResult<Exp>(resultExp, curPos);
    }

    public ParseResult<Exp> parseAdditiveExp(final int startPos) throws ParseException {
        final ParseResult<Exp> starting = parsePrimary(startPos);
        Exp resultExp = starting.result;
        return parseAdditiveExpHelper(starting.nextPos, resultExp);
    }

    public ParseResult<Exp> parseMultiplicative(final int startPos) throws ParseException {
        return null;
    }

    public ParseResult<Exp> parsePrimary(final int startPos) throws ParseException {
        final Token tokenHere = readToken(startPos);
        if (tokenHere instanceof VariableToken) {
            final VariableToken asVar = (VariableToken)tokenHere;
            return new ParseResult<Exp>(new VariableExp(asVar.getName()), startPos + 1);
        } else if(tokenHere instanceof IntToken) {
            final IntToken asInt = (IntToken) tokenHere;
            return new ParseResult<Exp>(new IntExp(asInt.getValue()), startPos + 1);
        } else if(tokenHere instanceof StringToken) {
            final StringToken asString = (StringToken) tokenHere;
            return parseString(tokenHere, startPos);
        }
        else {
            checkTokenIs(startPos, BracketsToken.TK_LPAREN);
            final ParseResult<Exp> inner = parseExp(startPos + 1);
            checkTokenIs(inner.nextPos, BracketsToken.TK_RPAREN);
            return new ParseResult<Exp>(inner.result,
                    inner.nextPos + 1);
        }
    }

    public ParseResult<Exp> parseString(final Token token, final int startPos) throws ParseException {
        String value = ((StringToken)token).getValue();
        String temp = value;
        int index = 0;
        int location = 0;
        LinkedHashMap<Integer, Exp> map = new LinkedHashMap<>();
        int length = value.length();
        String interpolation = "";
        boolean isBlockInterpolation = false;

        while(index < value.length()) {
            if(value.charAt(index) == '$') {
                if(index + 1 < length && value.charAt(index + 1) == '{') {
                    index += 2;
                    for(int i = index; i < length; i++) {
                        if(value.charAt(i) == '}') {
                            isBlockInterpolation = true;
                            break;
                        }
                    }
                    if(isBlockInterpolation) {
                        while (index < value.length() && value.charAt(index) != '}') {
                            interpolation += value.charAt(index);
                            index++;
                        }
                        index++;
//                        location++;
                    } else {
                        throw new ParseException("Invalid string interpolation! Expect }");
                    }
                } else if(index + 1 < length && Character.isLetter(value.charAt(index + 1))) {
                    index++;
                    interpolation += value.charAt(index);
                    index++;
                    while(index < length && Character.isLetterOrDigit(value.charAt(index))) {
                        interpolation += value.charAt(index);
                        index++;
                    }
                } else if(index + 1 < length && Character.isWhitespace(value.charAt(index + 1))){
                    index++;
                    location += 2;   // Now $ count
                }
                if(interpolation.length() > 0) {
                    try {
                        Tokenizer tokenizer = new Tokenizer(interpolation);
                        Token[] tokens = tokenizer.tokenize().stream().toArray(Token[]::new);
                        Parser parser = new Parser(tokens);
                        ParseResult<Exp> result = parser.parseExp(0);
                        map.put(location, result.result);
                        temp = temp.replace(isBlockInterpolation ? "${" + interpolation + "}" : "$" + interpolation, "");
                        isBlockInterpolation = false;
                        interpolation = "";
                    } catch (TokenizerException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                index++;
                location++;
            }
        }
        return new ParseResult<Exp>(map.size() > 0 ? new StringExp(temp, map) : new StringExp(temp, null), startPos + 1);
    }

    public  ParseResult<Exp> parseExp(final int startPos) throws ParseException {
        if(readToken(startPos) == KeywordToken.TK_IF) {
            checkTokenIs(startPos + 1, BracketsToken.TK_LPAREN);
            final ParseResult<Exp> condition = parseExp(startPos + 2);
            checkTokenIs(condition.nextPos, BracketsToken.TK_RPAREN);
            final ParseResult<Exp> ifTrue = parseExp(condition.nextPos + 1);
            if(tokens.length > ifTrue.nextPos && tokens[ifTrue.nextPos] == KeywordToken.TK_ELSE) {
                final ParseResult<Exp> ifFalse = parseExp(ifTrue.nextPos + 1);
                return new ParseResult<Exp>(new IfExp(condition.result, ifTrue.result, ifFalse.result),
                        ifFalse.nextPos);
            } else {
                return new ParseResult<Exp>(new IfExp(condition.result, ifTrue.result),
                        ifTrue.nextPos);
            }

        } else {
            return parseAdditiveExp(startPos);
        }
    }

    public Exp parseToplevelExp() throws ParseException {
        final ParseResult<Exp> result = parseExp(0);

        if(result.nextPos == tokens.length) {
            return result.result;
        } else {
            throw new ParseException("Extra tokens at end");
        }
    }

    public ParseResult<Stmt> parseStmt(final int startPos) throws ParseException {
        return null;
    }

    public ParseResult<Program> parseProgram(final int startPos) throws ParseException {
        return null;
    }
}