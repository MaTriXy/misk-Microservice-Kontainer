import React, { Dispatch, SetStateAction } from 'react';
import { Ace } from 'ace-builds';
import ace from 'ace-builds/src-noconflict/ace';
import 'ace-builds/src-noconflict/ext-language_tools';
import { Box, IconButton } from '@chakra-ui/react';
import { ViewState } from 'src/viewState';
import { CopyIcon } from '@chakra-ui/icons';
import { CommandParser } from '@web-actions/parsing/CommandParser';

interface Props {
  viewState: ViewState;
  setViewState: Dispatch<SetStateAction<ViewState>>;
}

export default class ResponseViewer extends React.Component<Props> {
  public refEditor: HTMLElement | null = null;
  public editor: Ace.Editor | null = null;

  constructor(props: Props) {
    super(props);
    this.copyToClipboard = this.copyToClipboard.bind(this);
  }

  componentDidMount() {
    this.editor = ace.edit(this.refEditor, {
      theme: 'ace/theme/textmate',
      mode: 'ace/mode/json',
      minLines: 10,
      enableBasicAutocompletion: true,
      enableLiveAutocompletion: true,
      highlightActiveLine: false,
      highlightGutterLine: false,
    });
    const editor = this.editor!;
    editor.setReadOnly(true);
    editor.resize();
  }

  public updateRef(item: HTMLDivElement | null) {
    this.refEditor = item;
  }

  async copyToClipboard() {
    try {
      const content = this.editor!.getValue();
      await navigator.clipboard.writeText(content);
    } catch (err) {
      console.error('Failed to copy with error:', err);
    }
  }

  public render() {
    this.editor?.setValue(this.props.viewState.response || '', -1);

    return (
      <>
        <IconButton
          aria-label="Copy"
          icon={<CopyIcon />}
          zIndex="100"
          position="absolute"
          top="14"
          right="2"
          backgroundColor="grey"
          onClick={this.copyToClipboard}
        />
        <Box
          id={'response-viewer'}
          width="100%"
          height="100%"
          ref={(it) => this.updateRef(it)}
        />
      </>
    );
  }
}
