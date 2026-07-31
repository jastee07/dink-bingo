package dinkbingo;

import lombok.Value;
/**
 * One actual item that can satisfy a logical bingo tile.
 */
@Value
public class BingoItem {

    int id;

    String name;
}
