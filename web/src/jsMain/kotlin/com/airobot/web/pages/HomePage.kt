package com.airobot.web.pages

import js.objects.jso
import mui.base.InputBaseProps
import mui.icons.material.Input
import mui.icons.material.Search
import mui.material.*
import mui.material.MuiAutocomplete.Companion.endAdornment
import mui.material.styles.TypographyVariant
import mui.system.Box
import mui.system.responsive
import mui.system.sx
import react.*
import react.dom.onChange
import web.cssom.*

val HomePage = FC<Props> {
    var searchQuery by useState("")

    Box {
        sx {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = AlignItems.center
            justifyContent = JustifyContent.center
            minHeight = 100.vh
            padding = 16.px
        }

        // Logo
        Box {
            sx {
                marginBottom = 32.px
            }
            Typography {
                variant = TypographyVariant.h3
                +"AI Robot"
            }
        }

        // Search Box
        Box {
            sx {
                width = responsive(584.px).asDynamic()
                maxWidth = 90.pct
            }
            TextField {
                fullWidth = true
                value = searchQuery
                onChange = { event ->
                    searchQuery = event.target.asDynamic().value
                }
                placeholder = "搜索设备或命令..."
                variant = FormControlVariant.outlined
                //Classifier 'InputProps' does not have a companion object, and thus must be initialized here
                asDynamic().InputProps = jso<InputProps> {
                    endAdornment = InputAdornment.create {
                        position = InputAdornmentPosition.end
                        IconButton {
                            Search()
                        }
                    }
                }
//                inputProps = jso<InputBaseComponentProps> {
//                    this.asDynamic().endAdornment = InputAdornment.create {
//                        position = InputAdornmentPosition.end
//                        IconButton {
//                            Search()
//                        }
//                    }
//                }

//                // Classifier 'InputProps' does not have a companion object, and thus must be initialized here
//                InputProps = jso<InputProps> {
//                    endAdornment = InputAdornment.create {
//                        position = InputAdornmentPosition.end
//                        IconButton {
//                            Search()
//                        }
//                    }
//                }
            }
        }
    }
}